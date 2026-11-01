//! JNI bridge over `ratatosk-core` for the Ratatosk Android app.
//!
//! The Kotlin side (`dev.ratatosk.core.RatatoskCore`) owns a `VpnService` and
//! its TUN file descriptor; it hands the raw fd to [`native_start`] and forgets
//! about packet plumbing. This crate then owns the fd: a reader thread poll()s
//! it into the userspace netstack, and the netstack's outgoing packets are
//! written straight back to it. No per-packet JNI, no packet callbacks into
//! the JVM.
//!
//! This mirrors `ratatosk-ios/crates/ffi` (the iOS Packet Tunnel bridge): the
//! core is the same, only the packet transport differs. Ping and subscription
//! download are exposed the same way they are on iOS, as JSON in/out.

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jlongArray, jstring};
use jni::JNIEnv;
use ratatosk_core::config::servers::config_from_link;
use ratatosk_core::netstack::device::channel_device;
use ratatosk_core::netstack::fakedns::{FakeDns, DEFAULT_POOL};
use ratatosk_core::netstack::flows::{FlowLog, DEFAULT_CAPACITY};
use ratatosk_core::netstack::Netstack;
use ratatosk_core::proxy::direct_dns::DirectResolver;
use ratatosk_core::proxy::dns_cache::DnsCache;
use ratatosk_core::proxy::stats::STATS;
use ratatosk_core::{ActiveServer, ProxyClient};
use std::ffi::{c_char, c_void, CString};
use std::os::fd::RawFd;
use std::os::raw::c_int;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{mpsc, watch};

/// Names the fake DNS hands out before it reuses the oldest.
const FAKE_DNS_CAPACITY: u32 = 16_384;
/// Packets written to the TUN in one poll round at most.
const PACKET_BATCH: usize = 64;
/// How long `write_all` waits on POLLOUT before retrying a blocked write.
const WRITE_POLL_MS: i32 = 200;
/// epoll event data tags: which fd woke the reader.
const EPOLL_TAG_TUN: u64 = 1;
const EPOLL_TAG_STOP: u64 = 2;

// ── Android logging ────────────────────────────────────────────────────────

extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

const ANDROID_LOG_INFO: c_int = 4;

/// Route the core's `tracing` output to logcat, once per process.
fn install_logger() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_max_level(tracing::Level::INFO)
            .with_ansi(false)
            .without_time()
            .with_target(false)
            .with_writer(|| LogcatWriter)
            .try_init();
    });
}

struct LogcatWriter;

impl std::io::Write for LogcatWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        for line in String::from_utf8_lossy(buf).lines() {
            if line.is_empty() {
                continue;
            }
            if let Ok(text) = CString::new(line) {
                unsafe {
                    __android_log_write(ANDROID_LOG_INFO, c"ratatosk".as_ptr(), text.as_ptr());
                }
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

// ── Engine (the same core the iOS Packet Tunnel runs) ──────────────────────

/// One running core. Fields private to this module; the JNI layer below is
/// the only user.
struct Engine {
    runtime: Option<tokio::runtime::Runtime>,
    connector: ProxyClient,
    flows: Arc<FlowLog>,
    to_stack: mpsc::Sender<Vec<u8>>,
    stop: watch::Sender<bool>,
}

type PacketCallback =
    extern "C" fn(ctx: *mut c_void, packets: *const *const u8, lens: *const usize, count: usize);

/// The caller's context pointer, safe to move into the core's worker thread:
/// the Android tunnel keeps the pointee alive until the engine is stopped.
#[derive(Clone, Copy)]
struct HostContext(usize);
unsafe impl Send for HostContext {}
unsafe impl Sync for HostContext {}

/// Start the core for a `vless://`, `h3://` or `vpn://` link with the packet
/// callback of the caller. Adapted from `crates/ffi/src/lib.rs::start`.
fn start_engine(
    link: &str,
    mtu: u16,
    direct_zones: &[String],
    on_packet: PacketCallback,
    ctx: HostContext,
) -> anyhow::Result<Engine> {
    let mut config = config_from_link(link, 0)?;
    config
        .routing
        .direct_suffix
        .extend(direct_zones.iter().cloned());
    if config.needs_xray_sidecar()? {
        anyhow::bail!(
            "this server needs xray-core (XTLS Vision over plain TLS), which Android cannot run"
        );
    }
    // A phone opens dozens of connections at once; Mux.Cool shares a few server
    // connections instead of one handshake each. Vision cannot be multiplexed.
    if config.server.vision_flow()?.is_none() {
        config.mux.enabled = true;
    }
    // Two workers: a phone has a tight memory budget, like the iOS extension.
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("ratatosk")
        .enable_all()
        .build()?;
    let _guard = runtime.enter();
    let connector = ProxyClient::new(
        ActiveServer::new(config.server.clone()),
        config.dns.clone(),
        config.runtime.clone(),
        &config.routing,
        &config.mux,
    )?
    // A `direct` domain must be resolved to a real IP. The system resolver
    // inside the tunnel is pointed at our FakeDNS, so ask upstream ourselves;
    // the app's own sockets bypass the tunnel, so no egress binding is needed.
    .with_direct_resolver(DirectResolver::new(
        direct_dns_upstream(&config.tun.dns),
        None,
        DnsCache::new(Default::default()),
    ));
    let flows = Arc::new(FlowLog::new(DEFAULT_CAPACITY));
    let netstack = Netstack {
        connector: connector.clone(),
        dns: Arc::new(FakeDns::new(DEFAULT_POOL, FAKE_DNS_CAPACITY)),
        mtu,
        flows: flows.clone(),
    };
    let (device, mut host) = channel_device();
    let (stop, shutdown) = watch::channel(false);
    runtime.spawn(async move {
        if let Err(e) = netstack.run(device, shutdown).await {
            tracing::error!("netstack stopped: {e:#}");
        }
    });
    runtime.spawn(async move {
        let mut batch: Vec<Vec<u8>> = Vec::with_capacity(PACKET_BATCH);
        let mut lens = Vec::with_capacity(PACKET_BATCH);
        while host.from_stack.recv_many(&mut batch, PACKET_BATCH).await > 0 {
            let ptrs: Vec<*const u8> = batch.iter().map(|p| p.as_ptr()).collect();
            lens.extend(batch.iter().map(Vec::len));
            on_packet(
                ctx.0 as *mut c_void,
                ptrs.as_ptr(),
                lens.as_ptr(),
                batch.len(),
            );
            lens.clear();
            batch.clear();
        }
    });
    tracing::info!("Tunnel core started for {}", config.server.endpoint());
    Ok(Engine {
        runtime: Some(runtime),
        connector,
        flows,
        to_stack: host.to_stack,
        stop,
    })
}

/// Everything one Android tunnel owns: the core engine, the TUN fd, and the
/// reader thread that feeds the stack.
struct AndroidTunnel {
    engine: Engine,
    ctx: *mut FdCtx,
    stop_flag: Arc<AtomicBool>,
    reader: Option<std::thread::JoinHandle<()>>,
    fd: RawFd,
    stop_event: RawFd,
}

struct FdCtx {
    fd: RawFd,
}

/// Write one batch of IP packets straight to the TUN fd. Called on a core
/// worker thread.
extern "C" fn write_to_tun(
    ctx: *mut c_void,
    packets: *const *const u8,
    lens: *const usize,
    count: usize,
) {
    if ctx.is_null() {
        return;
    }
    let fd = unsafe { (*(ctx as *const FdCtx)).fd };
    for i in 0..count {
        let (packet, len) = unsafe { (*packets.add(i), *lens.add(i)) };
        if packet.is_null() || len == 0 {
            continue;
        }
        let buf = unsafe { std::slice::from_raw_parts(packet, len) };
        write_all(fd, buf);
    }
}

/// write(2), retrying short writes and EINTR/EAGAIN (the fd is non-blocking).
fn write_all(fd: RawFd, mut buf: &[u8]) {
    while !buf.is_empty() {
        let n = unsafe { libc::write(fd, buf.as_ptr() as *const c_void, buf.len()) };
        if n > 0 {
            buf = &buf[n as usize..];
            continue;
        }
        let err = std::io::Error::last_os_error();
        match err.kind() {
            std::io::ErrorKind::Interrupted => continue,
            std::io::ErrorKind::WouldBlock => {
                let mut pfd = libc::pollfd {
                    fd,
                    events: libc::POLLOUT,
                    revents: 0,
                };
                unsafe { libc::poll(&mut pfd, 1, WRITE_POLL_MS) };
            }
            _ => break,
        }
    }
}

/// Write to the stop eventfd so the reader's `epoll_wait` returns and it
/// exits its loop promptly.
fn signal_stop(stop_event: RawFd) {
    let one: u64 = 1;
    unsafe {
        libc::write(
            stop_event,
            &one as *const u64 as *const c_void,
            std::mem::size_of::<u64>(),
        );
    }
}

/// Feed the TUN's packets into the stack until the stop event fires. The
/// reader blocks in `epoll_wait` with no timeout, so an idle tunnel wakes the
/// CPU only when a packet actually arrives or it is told to stop.
fn spawn_reader(
    fd: RawFd,
    to_stack: mpsc::Sender<Vec<u8>>,
    stop: Arc<AtomicBool>,
    stop_event: RawFd,
) -> std::io::Result<std::thread::JoinHandle<()>> {
    std::thread::Builder::new()
        .name("ratatosk-tun-read".to_string())
        .spawn(move || {
            let epoll_fd = unsafe { libc::epoll_create1(libc::EPOLL_CLOEXEC) };
            if epoll_fd < 0 {
                return;
            }
            let mut tun_ev = libc::epoll_event {
                events: libc::EPOLLIN as u32,
                u64: EPOLL_TAG_TUN,
            };
            if unsafe { libc::epoll_ctl(epoll_fd, libc::EPOLL_CTL_ADD, fd, &mut tun_ev) } < 0 {
                unsafe { libc::close(epoll_fd) };
                return;
            }
            let mut stop_ev = libc::epoll_event {
                events: libc::EPOLLIN as u32,
                u64: EPOLL_TAG_STOP,
            };
            if unsafe { libc::epoll_ctl(epoll_fd, libc::EPOLL_CTL_ADD, stop_event, &mut stop_ev) }
                < 0
            {
                unsafe { libc::close(epoll_fd) };
                return;
            }

            let mut buf = vec![0u8; 65_536];
            let mut events = [libc::epoll_event { events: 0, u64: 0 }; 16];
            while !stop.load(Ordering::Relaxed) {
                let n = unsafe {
                    libc::epoll_wait(
                        epoll_fd,
                        events.as_mut_ptr(),
                        events.len() as libc::c_int,
                        -1,
                    )
                };
                if n < 0 {
                    if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
                        continue;
                    }
                    break;
                }
                for ev in events.iter().take(n as usize) {
                    match ev.u64 {
                        EPOLL_TAG_STOP => {
                            unsafe { libc::close(epoll_fd) };
                            return;
                        }
                        EPOLL_TAG_TUN => drain_tun(fd, &to_stack, &mut buf),
                        _ => {}
                    }
                }
            }
            unsafe { libc::close(epoll_fd) };
        })
}

/// Read every queued packet off the TUN fd until it runs dry; the fd is
/// non-blocking, so this returns quickly when the kernel queue is empty.
fn drain_tun(fd: RawFd, to_stack: &mpsc::Sender<Vec<u8>>, buf: &mut [u8]) {
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        if n > 0 {
            let _ = to_stack.try_send(buf[..n as usize].to_vec());
            continue;
        }
        if n == 0 {
            return;
        }
        match std::io::Error::last_os_error().kind() {
            std::io::ErrorKind::Interrupted => continue,
            _ => return,
        }
    }
}

fn set_nonblocking(fd: RawFd) {
    let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if flags >= 0 {
        unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) };
    }
}

fn direct_dns_upstream(dns: &str) -> std::net::SocketAddr {
    if let Ok(addr) = dns.parse::<std::net::SocketAddr>() {
        return addr;
    }
    if let Ok(ip) = dns.parse::<std::net::IpAddr>() {
        return std::net::SocketAddr::new(ip, 53);
    }
    std::net::SocketAddr::from(([8, 8, 8, 8], 53))
}

// ── JNI entry points (`dev.ratatosk.core.RatatoskCore`) ────────────────────

fn jstring_of(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jstring_of(
        &mut env,
        format!("ratatosk-android {}", env!("CARGO_PKG_VERSION")),
    )
}

/// Start the tunnel for `link` on the raw TUN `fd`. Returns a non-zero handle
/// or 0 (with a Java exception thrown) on failure.
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    link: JString,
    mtu: jint,
    direct_zones: JString,
    tun_fd: jint,
) -> jlong {
    install_logger();
    match start_jni(&mut env, link, mtu, direct_zones, tun_fd) {
        Ok(handle) => handle,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", format!("{e:#}"));
            0
        }
    }
}

fn start_jni(
    env: &mut JNIEnv,
    link: JString,
    mtu: jint,
    direct_zones: JString,
    tun_fd: jint,
) -> anyhow::Result<jlong> {
    let link: String = env
        .get_string(&link)
        .map_err(|e| anyhow::anyhow!("link is not a valid Java string: {e}"))?
        .into();
    let zones: Vec<String> = if direct_zones.is_null() {
        Vec::new()
    } else {
        env.get_string(&direct_zones)
            .map(String::from)
            .unwrap_or_default()
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect()
    };
    let fd = tun_fd as RawFd;
    if fd < 0 {
        anyhow::bail!("invalid TUN fd {tun_fd}");
    }
    set_nonblocking(fd);

    // The reader blocks in epoll_wait until a packet or this stop signal;
    // writing to it wakes the reader for a clean, immediate shutdown.
    let stop_event = unsafe { libc::eventfd(0, libc::EFD_CLOEXEC) };
    if stop_event < 0 {
        unsafe { libc::close(fd) };
        anyhow::bail!("eventfd() failed: {}", std::io::Error::last_os_error());
    }

    let ctx = Box::into_raw(Box::new(FdCtx { fd }));
    let mut engine = match start_engine(
        &link,
        mtu as u16,
        &zones,
        write_to_tun,
        HostContext(ctx as usize),
    ) {
        Ok(engine) => engine,
        Err(e) => {
            // The fd was handed over by detachFd(): we own it, so close it.
            unsafe { drop(Box::from_raw(ctx)) };
            unsafe { libc::close(fd) };
            unsafe { libc::close(stop_event) };
            return Err(e);
        }
    };
    let stop_flag = Arc::new(AtomicBool::new(false));
    let reader = match spawn_reader(fd, engine.to_stack.clone(), stop_flag.clone(), stop_event) {
        Ok(reader) => reader,
        Err(e) => {
            // Stop the engine before freeing ctx/fd, as nativeStop does.
            let _ = engine.stop.send(true);
            if let Some(runtime) = engine.runtime.take() {
                runtime.shutdown_timeout(Duration::from_secs(2));
            }
            unsafe { drop(Box::from_raw(ctx)) };
            unsafe { libc::close(fd) };
            unsafe { libc::close(stop_event) };
            return Err(e.into());
        }
    };
    tracing::info!("Android tunnel started (mtu {mtu}, fd {fd})");
    let tunnel = AndroidTunnel {
        engine,
        ctx,
        stop_flag,
        reader: Some(reader),
        fd,
        stop_event,
    };
    Ok(Box::into_raw(Box::new(tunnel)) as jlong)
}

#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeStop(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let mut tunnel = unsafe { Box::from_raw(handle as *mut AndroidTunnel) };
    // Wake the reader's epoll_wait first so it exits promptly, then stop the
    // engine and join the thread.
    signal_stop(tunnel.stop_event);
    tunnel.stop_flag.store(true, Ordering::SeqCst);
    let _ = tunnel.engine.stop.send(true);
    if let Some(runtime) = tunnel.engine.runtime.take() {
        runtime.shutdown_timeout(Duration::from_secs(2));
    }
    if let Some(reader) = tunnel.reader.take() {
        let _ = reader.join();
    }
    unsafe { drop(Box::from_raw(tunnel.ctx)) };
    unsafe { libc::close(tunnel.fd) };
    unsafe { libc::close(tunnel.stop_event) };
    tracing::info!("Android tunnel stopped");
}

/// `[active, total, up, down, errors]`.
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeStats(
    env: JNIEnv,
    _class: JClass,
) -> jlongArray {
    let s = STATS.snapshot();
    let values = [
        s.active as i64,
        s.total as i64,
        s.up as i64,
        s.down as i64,
        s.errors as i64,
    ];
    match env.new_long_array(values.len() as i32) {
        Ok(array) => {
            let _ = env.set_long_array_region(&array, 0, &values);
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

/// The newest `max` flows as JSON, or null.
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeFlows(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    max: jint,
) -> jstring {
    let Some(tunnel) = (unsafe { (handle as *const AndroidTunnel).as_ref() }) else {
        return std::ptr::null_mut();
    };
    jstring_of(&mut env, tunnel.engine.flows.to_json(max.max(0) as usize))
}

/// The device's network changed (Wi-Fi to cellular, say): an h3 connection
/// moves to a new path instead of timing out.
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeNetworkChanged(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let Some(tunnel) = (unsafe { (handle as *const AndroidTunnel).as_ref() }) else {
        return;
    };
    if let Some(runtime) = tunnel.engine.runtime.as_ref() {
        let connector = tunnel.engine.connector.clone();
        runtime.spawn(async move { connector.network_changed().await });
    }
}

/// Handshake time per link in `links_json` (a JSON array), as a JSON array of
/// milliseconds with null where the server did not answer. Blocks.
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativePing(
    mut env: JNIEnv,
    _class: JClass,
    links_json: JString,
    timeout_ms: jint,
) -> jstring {
    let Ok(links_json) = env.get_string(&links_json) else {
        return std::ptr::null_mut();
    };
    let links_json: String = links_json.into();
    let Ok(links) = serde_json::from_str::<Vec<String>>(&links_json) else {
        return std::ptr::null_mut();
    };
    let servers: Vec<Option<ratatosk_core::ServerConfig>> = links
        .iter()
        .map(|link| config_from_link(link, 0).ok().map(|c| c.server))
        .collect();
    let valid: Vec<ratatosk_core::ServerConfig> = servers.iter().flatten().cloned().collect();
    let limit = Duration::from_millis(u64::from((timeout_ms.max(1)) as u32));
    let Ok(runtime) = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    else {
        return std::ptr::null_mut();
    };
    let measured = runtime.block_on(ratatosk_core::proxy::runtime::ping_servers(&valid, limit));
    let mut measured = measured.into_iter();
    let out: Vec<Option<u64>> = servers
        .iter()
        .map(|server| {
            server.as_ref()?;
            measured.next()?.ok().map(|d| d.as_millis() as u64)
        })
        .collect();
    jstring_of(&mut env, serde_json::Value::from(out).to_string())
}

/// Download a subscription (see `ratatosk_core::config::subscription::fetch`).
#[no_mangle]
pub extern "system" fn Java_dev_ratatosk_core_RatatoskCore_nativeFetchSubscription(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Ok(url) = env.get_string(&url) else {
        return std::ptr::null_mut();
    };
    let url: String = url.into();
    let result = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(anyhow::Error::from)
        .and_then(|runtime| runtime.block_on(ratatosk_core::config::subscription::fetch(&url)));
    let json = match result {
        Ok(sub) => {
            let info = sub.meta.userinfo.unwrap_or_default();
            serde_json::json!({
                "title": sub.meta.title,
                "links": sub.links,
                "skipped": sub.skipped,
                "upload": info.upload,
                "download": info.download,
                "total": info.total,
                "expire": info.expire,
                "interval_hours": sub.meta.interval_hours,
            })
        }
        Err(e) => serde_json::json!({ "error": format!("{e:#}") }),
    };
    jstring_of(&mut env, json.to_string())
}

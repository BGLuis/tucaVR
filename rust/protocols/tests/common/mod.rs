//! TCP proxy for pool tests: counts connections the client opens (one
//! authentication each) and can cut every live one to simulate a dropped link.
//! It is used instead of parsing container logs because it needs no extra
//! container settings and works the same for SMB and SFTP.
use std::io;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

pub struct TcpProxy {
    port: u16,
    accepted: Arc<AtomicUsize>,
    live: Arc<Mutex<Vec<TcpStream>>>,
}

impl TcpProxy {
    pub fn start(upstream_host: &str, upstream_port: u16) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").expect("proxy bind");
        let port = listener.local_addr().expect("proxy addr").port();
        let accepted = Arc::new(AtomicUsize::new(0));
        let live = Arc::new(Mutex::new(Vec::new()));
        let upstream = (upstream_host.to_string(), upstream_port);

        let (accepted_in_loop, live_in_loop) = (accepted.clone(), live.clone());
        thread::spawn(move || {
            for client in listener.incoming().flatten() {
                accepted_in_loop.fetch_add(1, Ordering::SeqCst);
                let Ok(server) = TcpStream::connect((upstream.0.as_str(), upstream.1)) else {
                    continue;
                };
                let _ = client.set_nodelay(true);
                let _ = server.set_nodelay(true);
                if let (Ok(c), Ok(s)) = (client.try_clone(), server.try_clone()) {
                    live_in_loop.lock().unwrap().extend([c, s]);
                }
                pipe(client.try_clone(), server.try_clone());
                pipe(server.try_clone(), client.try_clone());
            }
        });

        Self {
            port,
            accepted,
            live,
        }
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn connections(&self) -> usize {
        self.accepted.load(Ordering::SeqCst)
    }

    pub fn drop_connections(&self) {
        for stream in self.live.lock().unwrap().drain(..) {
            let _ = stream.shutdown(Shutdown::Both);
        }
    }
}

fn pipe(from: io::Result<TcpStream>, to: io::Result<TcpStream>) {
    let (Ok(mut from), Ok(mut to)) = (from, to) else {
        return;
    };
    thread::spawn(move || {
        let _ = io::copy(&mut from, &mut to);
        let _ = to.shutdown(Shutdown::Both);
    });
}

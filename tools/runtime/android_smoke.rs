// SPDX-License-Identifier: GPL-3.0-only
fn main() {
    println!(
        "{{\"probe\":\"xiyan-rust-android-smoke\",\"arch\":\"{}\",\"os\":\"{}\",\"pid\":{}}}",
        std::env::consts::ARCH,
        std::env::consts::OS,
        std::process::id()
    );
}

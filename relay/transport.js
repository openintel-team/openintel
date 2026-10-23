// Advertise TLS only to clients that can follow the redirect before sending credentials.
function attachRelayTransport(server, wss, secureWebSocketUrl = "") {
  let secureUrl = "";
  if (secureWebSocketUrl) {
    if (typeof secureWebSocketUrl !== "string" || /[\x00-\x20\x7f]/.test(secureWebSocketUrl)
        || secureWebSocketUrl.length > 2048) throw new Error("Invalid secureWebSocketUrl");
    const parsed = new URL(secureWebSocketUrl);
    if (parsed.protocol !== "wss:" || parsed.username || parsed.password || parsed.hash) {
      throw new Error("secureWebSocketUrl must be a WSS URL without credentials or a fragment");
    }
    secureUrl = parsed.href;
  }
  server.on("upgrade", (request, socket, head) => {
    if (secureUrl && request.headers["x-openintel-prefer-tls"] === "1") {
      socket.end("HTTP/1.1 307 Temporary Redirect\r\n"
        + `Location: ${secureUrl}\r\n`
        + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: 0\r\n\r\n");
      return;
    }
    wss.handleUpgrade(request, socket, head, client => wss.emit("connection", client, request));
  });
}

module.exports = { attachRelayTransport };

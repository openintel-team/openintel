const { lower } = require("./common");
function createSessions({ clients, users }) {
  const pendingByAddress = new Map();
  const socketsFor = name => [...clients()].filter(c => c.authedAs && lower(c.authedAs) === lower(name));
  return { pendingByAddress, socketsFor,
    currentUser: ws => ws.authedAs ? users.userByName(ws.authedAs) : null,
    revoke(name, reason = "session revoked") {
      const sockets = socketsFor(name); for (const ws of sockets) ws.close(4001, reason); return sockets.length;
    },
    pending(address, limit) {
      if (limit > 0 && (pendingByAddress.get(address) ?? 0) >= limit) return null;
      pendingByAddress.set(address, (pendingByAddress.get(address) ?? 0) + 1);
      let done = false;
      return () => {
        if (done) return; done = true;
        const left = (pendingByAddress.get(address) ?? 1) - 1;
        if (left > 0) pendingByAddress.set(address, left); else pendingByAddress.delete(address);
      };
    },
  };
}
module.exports = { createSessions };

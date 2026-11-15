const { lower, hashToken, sealUsers, validRole, validName } = require("./common");
function createUsers(store) {
  const sealed = sealUsers(store.load("users.json", { users: [] }));
  let data = sealed.data;
  const userByName = name => data.users.find(u => lower(u.name) === lower(name));
  function replace(next) {
    const candidate = sealUsers(next).data;
    store.save("users.json", candidate);
    data = candidate;
  }
  return {
    get data() { return data; },
    migrate() {
      if (sealed.changed) {
        store.save("users.json", data);
        console.log("users.json: replaced plaintext relay tokens with SHA-256 digests");
      }
    },
    replace,
    save() { store.save("users.json", data); },
    userByName,
    userByToken(token) {
      if (typeof token !== "string" || !token) return;
      const digest = hashToken(token);
      return data.users.find(u => !u.disabled && typeof u.tokenHash === "string" && lower(u.tokenHash) === digest);
    },
    names: () => data.users.filter(u => !u.disabled).map(u => u.name),
    add(name, role, token) {
      if (!validName(name) || !validRole(role) || userByName(name)) throw new Error("Invalid or existing user");
      const user = { name, role, tokenHash: hashToken(token) };
      replace({ ...data, users: [...data.users, user] });
      return user;
    },
    update(name, changes) {
      if (!userByName(name)) throw new Error("User not found");
      replace({ ...data, users: data.users.map(u => lower(u.name) === lower(name) ? { ...u, ...changes } : u) });
      return userByName(name);
    },
    remove(name) { replace({ ...data, users: data.users.filter(u => lower(u.name) !== lower(name)) }); },
  };
}
module.exports = { createUsers };

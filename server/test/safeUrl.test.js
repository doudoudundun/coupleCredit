const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const test = require("node:test");

const {
  downloadImageAsBuffer,
  isPublicIp,
  parseRemoteUrl,
} = require("../src/utils/safeUrl");

test("remote image validation rejects private, loopback, link-local, and internal IPv6 addresses", () => {
  for (const address of [
    "0.0.0.0",
    "10.0.0.1",
    "127.0.0.1",
    "169.254.1.1",
    "192.168.1.1",
    "::",
    "::1",
    "fec0::1",
    "::192.168.1.1",
    "64:ff9b::a00:1",
    "2002:7f00:1::",
    "fc00::1",
    "fe80::1",
    "::ffff:127.0.0.1",
  ]) {
    assert.equal(isPublicIp(address), false, address);
  }
  assert.equal(isPublicIp("93.184.216.34"), true);
  assert.equal(isPublicIp("2001:4860:4860::8888"), true);
  assert.equal(isPublicIp("::ffff:8.8.8.8"), true);
  assert.throws(() => parseRemoteUrl("file:///etc/passwd"), /HTTP/);
});

test("download resolves and connects to a validated address, rejecting a private redirect", async () => {
  let requests = 0;
  const requester = {
    request(_options, callback) {
      requests += 1;
      const request = new EventEmitter();
      request.destroy = () => {};
      request.end = () => process.nextTick(() => {
        const response = new EventEmitter();
        response.statusCode = 302;
        response.headers = { location: "http://127.0.0.1/secret" };
        response.resume = () => {};
        callback(response);
      });
      return request;
    },
  };

  await assert.rejects(
    downloadImageAsBuffer("http://public.example/image.png", {
      lookup: async () => [{ address: "93.184.216.34", family: 4 }],
      requester,
    }),
    (error) => error.code === "IMAGE_URL_BLOCKED"
  );
  assert.equal(requests, 1);
});

test("download follows only a bounded number of validated redirects", async () => {
  let requests = 0;
  const requester = {
    request(_options, callback) {
      requests += 1;
      const request = new EventEmitter();
      request.destroy = () => {};
      request.end = () => process.nextTick(() => {
        const response = new EventEmitter();
        response.statusCode = 302;
        response.headers = { location: "http://public.example/again" };
        response.resume = () => {};
        callback(response);
      });
      return request;
    },
  };

  await assert.rejects(
    downloadImageAsBuffer("http://public.example/start", {
      maxRedirects: 2,
      lookup: async () => [{ address: "93.184.216.34", family: 4 }],
      requester,
    }),
    (error) => error.code === "IMAGE_REDIRECT_LIMIT"
  );
  assert.equal(requests, 3);
});

const dns = require("node:dns");
const http = require("node:http");
const https = require("node:https");
const net = require("node:net");

const { ApiError } = require("../errors");

const MAX_REDIRECTS = 3;
const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

function parseRemoteUrl(rawUrl) {
  if (typeof rawUrl !== "string" || rawUrl.trim() === "") {
    throw new ApiError(400, "INVALID_IMAGE_URL", "图片地址无效");
  }

  let url;
  try {
    url = new URL(rawUrl);
  } catch (_error) {
    throw new ApiError(400, "INVALID_IMAGE_URL", "图片地址无效");
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new ApiError(400, "INVALID_IMAGE_URL", "仅支持 HTTP(S) 图片地址");
  }
  if (!url.hostname || url.username || url.password) {
    throw new ApiError(400, "INVALID_IMAGE_URL", "图片地址无效");
  }
  return url;
}

function ipv4ToInteger(address) {
  const parts = address.split(".");
  if (parts.length !== 4 || parts.some((part) => !/^\d{1,3}$/.test(part))) return null;
  const octets = parts.map(Number);
  if (octets.some((octet) => octet > 255)) return null;
  return (((octets[0] * 256 + octets[1]) * 256 + octets[2]) * 256 + octets[3]) >>> 0;
}

function inIpv4Range(value, start, end) {
  return value !== null && value >= start && value <= end;
}

function ipv6ToWords(address) {
  let value = String(address).toLowerCase();
  const zoneIndex = value.indexOf("%");
  if (zoneIndex >= 0) value = value.slice(0, zoneIndex);
  if (value.includes(".")) {
    const lastColon = value.lastIndexOf(":");
    const ipv4 = ipv4ToInteger(value.slice(lastColon + 1));
    if (ipv4 === null) return null;
    value = `${value.slice(0, lastColon)}:${((ipv4 >>> 16) & 0xffff).toString(16)}:${(ipv4 & 0xffff).toString(16)}`;
  }

  const pieces = value.split("::");
  if (pieces.length > 2) return null;
  const left = pieces[0] ? pieces[0].split(":") : [];
  const right = pieces.length === 2 && pieces[1] ? pieces[1].split(":") : [];
  if (left.concat(right).some((part) => !/^[0-9a-f]{1,4}$/.test(part))) return null;
  const missing = 8 - left.length - right.length;
  if ((pieces.length === 1 && missing !== 0) || missing < 0) return null;
  return left.concat(new Array(missing).fill("0"), right).map((part) => parseInt(part || "0", 16));
}

function startsWithIpv6Prefix(words, prefix, bits) {
  if (!words) return false;
  const wholeWords = Math.floor(bits / 16);
  const remainingBits = bits % 16;
  for (let i = 0; i < wholeWords; i++) {
    if (words[i] !== prefix[i]) return false;
  }
  if (remainingBits === 0) return true;
  const mask = (0xffff << (16 - remainingBits)) & 0xffff;
  return (words[wholeWords] & mask) === (prefix[wholeWords] & mask);
}

function normalizeHostname(hostname) {
  const value = String(hostname || "");
  return value.startsWith("[") && value.endsWith("]") ? value.slice(1, -1) : value;
}

function isPublicIp(address) {
  const normalizedAddress = normalizeHostname(address);
  const family = net.isIP(normalizedAddress);
  if (family === 4) {
    const value = ipv4ToInteger(normalizedAddress);
    return [
      [0x00000000, 0x00ffffff], // "this" network / unspecified
      [0x0a000000, 0x0affffff], // RFC 1918
      [0x64400000, 0x647fffff], // RFC 6598 shared address space
      [0x7f000000, 0x7fffffff], // loopback
      [0xa9fe0000, 0xa9feffff], // link-local
      [0xac100000, 0xac1fffff], // RFC 1918
      [0xc0000000, 0xc00000ff], // IETF protocol assignments
      [0xc0000200, 0xc00002ff], // TEST-NET-1
      [0xc0586300, 0xc05863ff], // deprecated 6to4 relay anycast
      [0xc0a80000, 0xc0a8ffff], // RFC 1918
      [0xc6120000, 0xc613ffff], // benchmark testing
      [0xc6336400, 0xc63364ff], // TEST-NET-2
      [0xcb007100, 0xcb0071ff], // TEST-NET-3
      [0xe0000000, 0xffffffff], // multicast/reserved
    ].every(([start, end]) => !inIpv4Range(value, start, end));
  }
  if (family === 6) {
    const words = ipv6ToWords(normalizedAddress);
    if (!words) return false;
    // Unspecified, loopback, ULA, link-local, multicast, documentation, and
    // IPv4-mapped private addresses are never valid remote image targets.
    if (words.every((word) => word === 0)) return false;
    if (words.slice(0, 7).every((word) => word === 0) && words[7] === 1) return false;
    const isIpv4Mapped = words.slice(0, 5).every((word) => word === 0) && words[5] === 0xffff;
    if (isIpv4Mapped) {
      const mapped = `${words[6] >>> 8}.${words[6] & 0xff}.${words[7] >>> 8}.${words[7] & 0xff}`;
      return isPublicIp(mapped);
    }
    // Only global-unicast 2000::/3 destinations are accepted.  This
    // conservative boundary also excludes IPv4-compatible, site-local,
    // discard, and otherwise special IPv6 ranges by default.
    if (!startsWithIpv6Prefix(words, [0x2000, 0, 0, 0, 0, 0, 0, 0], 3)) return false;
    if (startsWithIpv6Prefix(words, [0xfc00, 0, 0, 0, 0, 0, 0, 0], 7)) return false;
    if (startsWithIpv6Prefix(words, [0xfec0, 0, 0, 0, 0, 0, 0, 0], 10)) return false;
    if (startsWithIpv6Prefix(words, [0xfe80, 0, 0, 0, 0, 0, 0, 0], 10)) return false;
    if (startsWithIpv6Prefix(words, [0xff00, 0, 0, 0, 0, 0, 0, 0], 8)) return false;
    if (startsWithIpv6Prefix(words, [0x0064, 0xff9b, 0, 0, 0, 0, 0, 0], 96)) return false;
    if (startsWithIpv6Prefix(words, [0x0064, 0xff9b, 0x0001, 0, 0, 0, 0, 0], 48)) return false;
    if (startsWithIpv6Prefix(words, [0x2001, 0, 0, 0, 0, 0, 0, 0], 32)) return false;
    if (startsWithIpv6Prefix(words, [0x2001, 2, 0, 0, 0, 0, 0, 0], 48)) return false;
    if (startsWithIpv6Prefix(words, [0x2001, 0xdb8, 0, 0, 0, 0, 0, 0], 32)) return false;
    if (startsWithIpv6Prefix(words, [0x2001, 0x10, 0, 0, 0, 0, 0, 0], 28)) return false;
    if (startsWithIpv6Prefix(words, [0x2001, 0x20, 0, 0, 0, 0, 0, 0], 28)) return false;
    if (startsWithIpv6Prefix(words, [0x2002, 0, 0, 0, 0, 0, 0, 0], 16)) return false;
    // IPv4-compatible ::/96 (which is not covered by the mapped ::ffff/96
    // handling above) can carry a private IPv4 address.
    if (words.slice(0, 6).every((word) => word === 0)) return false;
    return true;
  }
  return false;
}

async function resolvePublicAddresses(hostname, lookup = dns.promises.lookup) {
  const normalizedHostname = normalizeHostname(hostname);
  const family = net.isIP(normalizedHostname);
  let records;
  if (family) {
    records = [{ address: normalizedHostname, family }];
  } else {
    try {
      records = await lookup(normalizedHostname, { all: true, verbatim: true });
    } catch (error) {
      throw new ApiError(502, "IMAGE_DNS_ERROR", "图片地址解析失败");
    }
  }

  if (!Array.isArray(records) || records.length === 0 || records.some((record) => !record || !isPublicIp(record.address))) {
    throw new ApiError(403, "IMAGE_URL_BLOCKED", "图片地址指向受限制的网络地址");
  }
  return records;
}

function requestOnce(url, address, {
  timeoutMs,
  maxBytes,
  requester,
} = {}) {
  return new Promise((resolve, reject) => {
    const isHttps = url.protocol === "https:";
    const client = requester || (isHttps ? https : http);
    let settled = false;
    let request;

    const fail = (error) => {
      if (settled) return;
      settled = true;
      if (request) request.destroy();
      reject(error);
    };

    const options = {
      hostname: address.address,
      family: address.family,
      port: url.port || (isHttps ? 443 : 80),
      path: `${url.pathname}${url.search}`,
      method: "GET",
      timeout: timeoutMs,
      // Connect to the address resolved above and retain the original host for
      // virtual hosting.  This prevents a second DNS lookup at socket time.
      headers: {
        Host: url.host,
        "User-Agent": "Mozilla/5.0 (compatible; ImageDownloader/1.0)",
      },
    };
    if (isHttps) options.servername = normalizeHostname(url.hostname);

    try {
      request = client.request(options, (response) => {
        const statusCode = Number(response.statusCode || 0);
        const headers = response.headers || {};
        if (statusCode >= 300 && statusCode < 400 && headers.location) {
          response.resume();
          settled = true;
          resolve({ statusCode, headers, location: headers.location });
          return;
        }
        if (statusCode >= 400 || statusCode < 200) {
          response.resume();
          settled = true;
          resolve({ statusCode, headers });
          return;
        }

        const contentLength = Number(headers["content-length"]);
        if (Number.isFinite(contentLength) && contentLength > maxBytes) {
          response.resume();
          fail(new ApiError(413, "IMAGE_TOO_LARGE", "下载图片超过大小限制"));
          return;
        }

        const chunks = [];
        let totalBytes = 0;
        response.on("data", (chunk) => {
          totalBytes += chunk.length;
          if (totalBytes > maxBytes) {
            response.destroy();
            fail(new ApiError(413, "IMAGE_TOO_LARGE", "下载图片超过大小限制"));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          if (settled) return;
          settled = true;
          resolve({ statusCode, headers, body: Buffer.concat(chunks) });
        });
        response.on("error", (error) => fail(new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `下载图片失败: ${error.message}`)));
      });
    } catch (error) {
      fail(new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `下载图片失败: ${error.message}`));
      return;
    }
    request.on("error", (error) => fail(new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `下载图片失败: ${error.message}`)));
    request.on("timeout", () => fail(new ApiError(504, "IMAGE_DOWNLOAD_TIMEOUT", "下载图片超时")));
    request.end();
  });
}

async function downloadImageAsBuffer(imageUrl, options = {}) {
  const maxRedirects = Number.isInteger(options.maxRedirects) ? options.maxRedirects : MAX_REDIRECTS;
  const timeoutMs = Number.isInteger(options.timeoutMs) ? options.timeoutMs : DEFAULT_TIMEOUT_MS;
  const maxBytes = Number.isInteger(options.maxBytes) ? options.maxBytes : DEFAULT_MAX_BYTES;
  const lookup = options.lookup || dns.promises.lookup;
  let url = parseRemoteUrl(imageUrl);

  for (let redirect = 0; ; redirect++) {
    const addresses = await resolvePublicAddresses(url.hostname, lookup);
    const response = await requestOnce(url, addresses[0], {
      timeoutMs,
      maxBytes,
      requester: options.requester,
    });
    if (response.location) {
      if (redirect >= maxRedirects) {
        throw new ApiError(502, "IMAGE_REDIRECT_LIMIT", "图片重定向次数过多");
      }
      let nextUrl;
      try {
        nextUrl = new URL(response.location, url);
      } catch (_error) {
        throw new ApiError(502, "IMAGE_DOWNLOAD_ERROR", "图片重定向地址无效");
      }
      url = parseRemoteUrl(nextUrl.toString());
      continue;
    }
    if (response.statusCode >= 400 || response.statusCode < 200) {
      throw new ApiError(502, "IMAGE_DOWNLOAD_ERROR", `无法下载图片，HTTP ${response.statusCode}`);
    }
    return response.body;
  }
}

module.exports = {
  DEFAULT_MAX_BYTES,
  MAX_REDIRECTS,
  parseRemoteUrl,
  isPublicIp,
  resolvePublicAddresses,
  downloadImageAsBuffer,
};

/*
 * Cliente mínimo del protocolo ADB, el mismo que AdbConnection.java pero en JavaScript.
 * El transporte se elige aparte: WebUSB en el navegador (Chrome/Edge) o TCP en Node (pruebas).
 * Un transporte tiene write(Uint8Array) y read(n) -> Uint8Array con exactamente n bytes.
 */
(function (root) {
  'use strict';

  const CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
  const OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
  const AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
  const VERSION = 0x01000000;
  const MAX_PAYLOAD = 256 * 1024;
  const SYNC_CHUNK = 64 * 1024;
  const KEY_BITS = 2048;
  // DigestInfo de SHA-1: adbd firma el token de 20 bytes como si fuera un hash SHA-1
  const SHA1_PREFIX = [0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14];

  const enc = new TextEncoder();
  const dec = new TextDecoder();

  // ---- RSA con BigInt -------------------------------------------------------------------
  // WebCrypto no deja firmar un hash ya calculado, así que la firma se hace a mano con la clave
  // que genera WebCrypto (exportada como JWK).

  function b64urlToBigInt(s) {
    const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
    let hex = '';
    for (let i = 0; i < bin.length; i++) hex += bin.charCodeAt(i).toString(16).padStart(2, '0');
    return BigInt('0x' + (hex || '0'));
  }

  function bigIntToBytes(x, len) {
    const out = new Uint8Array(len);
    for (let i = len - 1; i >= 0; i--) {
      out[i] = Number(x & 0xffn);
      x >>= 8n;
    }
    return out;
  }

  function bytesToBigInt(bytes) {
    let x = 0n;
    for (const b of bytes) x = (x << 8n) | BigInt(b);
    return x;
  }

  function modPow(base, exp, mod) {
    let result = 1n;
    base %= mod;
    while (exp > 0n) {
      if (exp & 1n) result = (result * base) % mod;
      base = (base * base) % mod;
      exp >>= 1n;
    }
    return result;
  }

  function modInverse(a, m) {
    let [r0, r1, s0, s1] = [((a % m) + m) % m, m, 1n, 0n];
    while (r1 !== 0n) {
      const q = r0 / r1;
      [r0, r1] = [r1, r0 - q * r1];
      [s0, s1] = [s1, s0 - q * s1];
    }
    return ((s0 % m) + m) % m;
  }

  function toBase64(bytes) {
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }

  class AdbKey {
    constructor(jwk) {
      this.jwk = jwk;
      this.n = b64urlToBigInt(jwk.n);
      this.e = b64urlToBigInt(jwk.e);
      this.p = b64urlToBigInt(jwk.p);
      this.q = b64urlToBigInt(jwk.q);
      this.dp = b64urlToBigInt(jwk.dp);
      this.dq = b64urlToBigInt(jwk.dq);
      this.qi = b64urlToBigInt(jwk.qi);
    }

    /** Carga la clave guardada o crea una nueva. store: {get(k), set(k, v)} (puede fallar). */
    static async loadOrCreate(store) {
      try {
        const saved = store && store.get('adbkey');
        if (saved) return new AdbKey(JSON.parse(saved));
      } catch (e) { /* sin almacenamiento: clave nueva */ }
      const pair = await crypto.subtle.generateKey(
        { name: 'RSASSA-PKCS1-v1_5', modulusLength: KEY_BITS, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-1' },
        true, ['sign', 'verify']);
      const jwk = await crypto.subtle.exportKey('jwk', pair.privateKey);
      try {
        if (store) store.set('adbkey', JSON.stringify(jwk));
      } catch (e) { /* se pedirá permiso en la TV cada vez */ }
      return new AdbKey(jwk);
    }

    /** Firma PKCS#1 v1.5 del token (relleno y DigestInfo a mano, como AdbKey.java). */
    sign(token) {
      const k = KEY_BITS / 8;
      const em = new Uint8Array(k);
      em[0] = 0; em[1] = 1;
      const tLen = SHA1_PREFIX.length + token.length;
      em.fill(0xff, 2, k - tLen - 1);
      em[k - tLen - 1] = 0;
      em.set(SHA1_PREFIX, k - tLen);
      em.set(token, k - token.length);
      // Teorema chino del resto: unas 4 veces más rápido que m^d mod n
      const m = bytesToBigInt(em);
      const m1 = modPow(m, this.dp, this.p);
      const m2 = modPow(m, this.dq, this.q);
      const h = (this.qi * (((m1 - m2) % this.p) + this.p)) % this.p;
      return bigIntToBytes(m2 + h * this.q, k);
    }

    /** Clave pública en el formato RSAPublicKey de mincrypt que espera adbd, en base64 y con \0. */
    publicKeyPayload(name) {
      const words = KEY_BITS / 32;
      const r32 = 1n << 32n;
      const n0inv = r32 - modInverse(this.n % r32, r32);
      let rr = modPow(1n << BigInt(KEY_BITS), 2n, this.n);
      let n = this.n;
      const buf = new DataView(new ArrayBuffer(4 + 4 + words * 8 + 4));
      let off = 0;
      const put = (v) => { buf.setUint32(off, Number(v), true); off += 4; };
      put(words);
      put(n0inv % r32);
      for (let i = 0; i < words; i++) { put(n % r32); n >>= 32n; }
      for (let i = 0; i < words; i++) { put(rr % r32); rr >>= 32n; }
      put(this.e);
      const text = enc.encode(toBase64(new Uint8Array(buf.buffer)) + ' ' + (name || 'debloattv@web'));
      const out = new Uint8Array(text.length + 1);
      out.set(text);
      return out;
    }
  }

  // ---- Protocolo ------------------------------------------------------------------------

  class AdbConnection {
    constructor(transport) {
      this.t = transport;
      this.maxPayload = 4096;
      this.nextLocalId = 1;
      this.queue = Promise.resolve();
      this.banner = '';
    }

    /** onWaiting se llama cuando la TV muestra «¿Permitir depuración?». */
    static async connect(transport, key, onWaiting) {
      const c = new AdbConnection(transport);
      await c.send(CNXN, VERSION, MAX_PAYLOAD, enc.encode('host::\0'));
      let signed = false, sentKey = false;
      for (;;) {
        const m = await c.read();
        if (m.command === CNXN) {
          c.maxPayload = Math.min(m.arg1, MAX_PAYLOAD);
          c.banner = dec.decode(m.data);
          return c;
        }
        if (m.command !== AUTH || m.arg0 !== AUTH_TOKEN) continue;
        if (!signed) {
          // Si la TV ya conoce nuestra clave, con la firma basta
          await c.send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
          signed = true;
        } else if (!sentKey) {
          await c.send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload());
          sentKey = true;
          if (onWaiting) onWaiting();
        } else {
          throw new Error('La TV no aceptó la clave de este navegador.');
        }
      }
    }

    /** Una operación a la vez, como el cliente Java. */
    exclusive(fn) {
      const run = this.queue.then(fn, fn);
      this.queue = run.catch(() => {});
      return run;
    }

    /** Ejecuta un comando en la TV y devuelve su salida (stdout y stderr juntos). */
    shell(command) {
      return this.exclusive(async () => {
        const local = this.nextLocalId++;
        await this.send(OPEN, local, 0, enc.encode('shell:' + command + '\0'));
        const chunks = [];
        for (;;) {
          const m = await this.read();
          if (m.arg1 !== local) continue;
          if (m.command === WRTE) {
            chunks.push(m.data);
            await this.send(OKAY, local, m.arg0);
          } else if (m.command === CLSE) {
            if (m.arg0 !== 0) await this.send(CLSE, local, m.arg0);
            return dec.decode(concat(chunks)).replace(/\r/g, '').trim();
          }
        }
      });
    }

    /** Copia un archivo a la TV con el servicio «sync:» (como «adb push»). */
    push(data, remotePath, mode) {
      return this.exclusive(async () => {
        const local = this.nextLocalId++;
        await this.send(OPEN, local, 0, enc.encode('sync:\0'));
        const remote = await this.awaitOkay(local);

        let buf = new Uint8Array(this.maxPayload), len = 0;
        const flush = async () => {
          if (len === 0) return;
          await this.send(WRTE, local, remote, buf.slice(0, len));
          len = 0;
          await this.awaitOkay(local);
        };
        const write = async (bytes) => {
          let off = 0;
          while (off < bytes.length) {
            const k = Math.min(bytes.length - off, buf.length - len);
            buf.set(bytes.subarray(off, off + k), len);
            len += k; off += k;
            if (len === buf.length) await flush();
          }
        };
        const spec = enc.encode(remotePath + ',' + (mode || 0o100644));
        await write(syncHeader('SEND', spec.length));
        await write(spec);
        for (let off = 0; off < data.length; off += SYNC_CHUNK) {
          const piece = data.subarray(off, Math.min(off + SYNC_CHUNK, data.length));
          await write(syncHeader('DATA', piece.length));
          await write(piece);
        }
        await write(syncHeader('DONE', Math.floor(Date.now() / 1000)));
        await flush();

        const reply = [];
        let got = 0;
        while (got < 8) {
          const m = await this.read();
          if (m.arg1 !== local) continue;
          if (m.command === WRTE) {
            reply.push(m.data); got += m.data.length;
            await this.send(OKAY, local, remote);
          } else if (m.command === CLSE) {
            throw new Error('La TV cerró la copia del archivo');
          }
        }
        const r = concat(reply);
        await this.send(CLSE, local, remote);
        const status = dec.decode(r.subarray(0, 4));
        if (status !== 'OKAY') throw new Error('No se pudo copiar el archivo: ' + (r.length > 8 ? dec.decode(r.subarray(8)) : status));
      });
    }

    async awaitOkay(local) {
      for (;;) {
        const m = await this.read();
        if (m.arg1 !== local) continue;
        if (m.command === OKAY) return m.arg0;
        if (m.command === CLSE) throw new Error('La TV rechazó la operación');
      }
    }

    async send(command, arg0, arg1, data) {
      data = data || new Uint8Array(0);
      let checksum = 0;
      for (let i = 0; i < data.length; i++) checksum = (checksum + data[i]) >>> 0;
      const h = new DataView(new ArrayBuffer(24));
      h.setUint32(0, command, true);
      h.setUint32(4, arg0 >>> 0, true);
      h.setUint32(8, arg1 >>> 0, true);
      h.setUint32(12, data.length, true);
      h.setUint32(16, checksum, true);
      h.setUint32(20, (~command) >>> 0, true);
      await this.t.write(new Uint8Array(h.buffer));
      if (data.length) await this.t.write(data);
    }

    async read() {
      const raw = await this.t.read(24);
      const h = new DataView(raw.buffer, raw.byteOffset, 24);
      const m = { command: h.getUint32(0, true), arg0: h.getUint32(4, true), arg1: h.getUint32(8, true) };
      const length = h.getUint32(12, true);
      if (length > 16 * 1024 * 1024) throw new Error('Respuesta ADB no válida');
      m.data = length ? await this.t.read(length) : new Uint8Array(0);
      return m;
    }

    async close() {
      try { await this.t.close(); } catch (e) { /* ya cerrada */ }
    }
  }

  function syncHeader(id, value) {
    const v = new DataView(new ArrayBuffer(8));
    for (let i = 0; i < 4; i++) v.setUint8(i, id.charCodeAt(i));
    v.setUint32(4, value >>> 0, true);
    return new Uint8Array(v.buffer);
  }

  function concat(chunks) {
    let n = 0;
    for (const c of chunks) n += c.length;
    const out = new Uint8Array(n);
    let off = 0;
    for (const c of chunks) { out.set(c, off); off += c.length; }
    return out;
  }

  // ---- Transporte WebUSB ----------------------------------------------------------------

  /** La interfaz ADB de un dispositivo Android: clase 0xff, subclase 0x42, protocolo 1. */
  const USB_FILTER = { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x01 };

  class WebUsbTransport {
    static supported() {
      return typeof navigator !== 'undefined' && !!navigator.usb;
    }

    /** Muestra el selector de dispositivos USB del navegador (requiere un clic del usuario). */
    static async request() {
      const device = await navigator.usb.requestDevice({ filters: [USB_FILTER] });
      return WebUsbTransport.open(device);
    }

    static async open(device) {
      await device.open();
      if (device.configuration === null) await device.selectConfiguration(1);
      for (const iface of device.configuration.interfaces) {
        for (const alt of iface.alternates) {
          if (alt.interfaceClass !== USB_FILTER.classCode || alt.interfaceSubclass !== USB_FILTER.subclassCode
              || alt.interfaceProtocol !== USB_FILTER.protocolCode) continue;
          try {
            await device.claimInterface(iface.interfaceNumber);
          } catch (e) {
            throw new Error('Otro programa está usando la conexión USB (cierra «adb» con «adb kill-server» y vuelve a intentarlo).');
          }
          if (alt.alternateSetting !== 0) await device.selectAlternateInterface(iface.interfaceNumber, alt.alternateSetting);
          const t = new WebUsbTransport(device, iface.interfaceNumber);
          for (const ep of alt.endpoints) {
            if (ep.type !== 'bulk') continue;
            if (ep.direction === 'in') t.epIn = ep.endpointNumber;
            else { t.epOut = ep.endpointNumber; t.packetSize = ep.packetSize; }
          }
          if (t.epIn == null || t.epOut == null) break;
          return t;
        }
      }
      await device.close();
      throw new Error('Este dispositivo no tiene la depuración USB activa.');
    }

    constructor(device, iface) {
      this.device = device;
      this.iface = iface;
      this.packetSize = 512;
      this.pending = new Uint8Array(0);
      this.name = (device.manufacturerName || '') + ' ' + (device.productName || '');
    }

    async write(data) {
      await this.device.transferOut(this.epOut, data);
      // Un bloque que llena justo los paquetes USB se cierra con uno vacío, como hace adb
      if (data.length > 0 && data.length % this.packetSize === 0) await this.device.transferOut(this.epOut, new Uint8Array(0));
    }

    async read(n) {
      const parts = [];
      let got = 0;
      while (got < n) {
        const r = await this.device.transferIn(this.epIn, n - got);
        if (r.status !== 'ok') throw new Error('Se cortó la conexión USB');
        const b = new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
        parts.push(b); got += b.length;
      }
      return concat(parts);
    }

    async close() {
      try { await this.device.releaseInterface(this.iface); } catch (e) { /* ya liberada */ }
      await this.device.close();
    }
  }

  const api = { AdbKey, AdbConnection, WebUsbTransport, concat };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.DebloatAdb = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);

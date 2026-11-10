(() => {
  "use strict";

  // Android injects this object only into the trusted vscode.dev origin.
  if (!window.AndroidFs || window.__androidFsShimInstalled) return;
  window.__androidFsShimInstalled = true;

  const pending = new Map();
  let requestCounter = 0;
  let selectedDirectory = null;

  function nativeCall(method, args = {}) {
    return new Promise((resolve, reject) => {
      const id = String(++requestCounter);
      pending.set(id, { resolve, reject });
      window.AndroidFs.postMessage(JSON.stringify({ id, method, args }));
    });
  }

  window.AndroidFs.onmessage = (event) => {
    let response;
    try {
      response = JSON.parse(event.data);
    } catch {
      return;
    }

    const item = pending.get(String(response.id));
    if (!item) return;
    pending.delete(String(response.id));

    if (response.ok) {
      item.resolve(response.data);
    } else {
      item.reject(new DOMException(
        response.error || "Android storage operation failed",
        response.errorName || "NotAllowedError"
      ));
    }
  };

  function decodeBase64(value) {
    const binary = atob(value || "");
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  }

  function encodeBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + chunkSize));
    }
    return btoa(binary);
  }

  async function toBytes(value) {
    if (typeof value === "string") return new TextEncoder().encode(value);
    if (value instanceof Blob) return new Uint8Array(await value.arrayBuffer());
    if (value instanceof ArrayBuffer) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) {
      return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    }
    throw new TypeError("Unsupported file data type");
  }

  function safeName(name) {
    if (typeof name !== "string" || !name || name === "." || name === ".." || /[\/\\]/.test(name)) {
      throw new TypeError("Invalid file or directory name");
    }
    return name;
  }

  function directoryHandle(uri, name, segments = []) {
    return {
      kind: "directory",
      name,
      _uri: uri,
      _segments: segments,

      async queryPermission() { return "granted"; },
      async requestPermission() { return "granted"; },

      async isSameEntry(other) {
        return Boolean(other && other.kind === "directory" && other._uri === uri);
      },

      async getDirectoryHandle(childName, options = {}) {
        safeName(childName);
        let child = (await nativeCall("list", { uri })).find((entry) => entry.name === childName);

        if (!child && options.create) {
          child = await nativeCall("createDirectory", { parentUri: uri, name: childName });
        }

        if (!child || !child.isDirectory) {
          throw new DOMException("Directory not found", "NotFoundError");
        }

        return directoryHandle(child.uri, child.name, [...segments, child.name]);
      },

      async getFileHandle(childName, options = {}) {
        safeName(childName);
        let child = (await nativeCall("list", { uri })).find((entry) => entry.name === childName);

        if (!child && options.create) {
          child = await nativeCall("createFile", {
            parentUri: uri,
            name: childName,
            mimeType: "application/octet-stream"
          });
        }

        if (!child || child.isDirectory) {
          throw new DOMException("File not found", "NotFoundError");
        }

        return fileHandle(child.uri, child.name, child.mimeType, [...segments, child.name]);
      },

      async removeEntry(childName, options = {}) {
        safeName(childName);
        const child = (await nativeCall("list", { uri })).find((entry) => entry.name === childName);
        if (!child) throw new DOMException("Entry not found", "NotFoundError");

        await nativeCall("delete", {
          uri: child.uri,
          recursive: Boolean(options.recursive)
        });
      },

      async resolve(possibleDescendant) {
        if (!possibleDescendant || !Array.isArray(possibleDescendant._segments)) return null;
        const child = possibleDescendant._segments;
        if (child.length < segments.length) return null;

        for (let index = 0; index < segments.length; index += 1) {
          if (segments[index] !== child[index]) return null;
        }

        return child.slice(segments.length);
      },

      async *entries() {
        const children = await nativeCall("list", { uri });
        for (const child of children) {
          const childSegments = [...segments, child.name];
          const handle = child.isDirectory
            ? directoryHandle(child.uri, child.name, childSegments)
            : fileHandle(child.uri, child.name, child.mimeType, childSegments);
          yield [child.name, handle];
        }
      },

      async *values() {
        for await (const [, handle] of this.entries()) yield handle;
      },

      async *keys() {
        for await (const [childName] of this.entries()) yield childName;
      },

      [Symbol.asyncIterator]() {
        return this.entries();
      }
    };
  }

  function fileHandle(uri, name, mimeType = "application/octet-stream", segments = []) {
    return {
      kind: "file",
      name,
      _uri: uri,
      _segments: segments,

      async queryPermission() { return "granted"; },
      async requestPermission() { return "granted"; },

      async isSameEntry(other) {
        return Boolean(other && other.kind === "file" && other._uri === uri);
      },

      async getFile() {
        const result = await nativeCall("read", { uri });
        return new File([decodeBase64(result.base64)], name, {
          type: result.mimeType || mimeType,
          lastModified: result.lastModified || Date.now()
        });
      },

      async createWritable(options = {}) {
        let bytes = new Uint8Array(0);
        if (options.keepExistingData) {
          const oldFile = await this.getFile();
          bytes = new Uint8Array(await oldFile.arrayBuffer());
        }

        let position = 0;
        let closed = false;

        function ensureOpen() {
          if (closed) throw new DOMException("Stream is closed", "InvalidStateError");
        }

        function truncate(size) {
          const newSize = Math.max(0, Number(size) || 0);
          const resized = new Uint8Array(newSize);
          resized.set(bytes.subarray(0, newSize));
          bytes = resized;
          position = Math.min(position, newSize);
        }

        return {
          async write(chunk) {
            ensureOpen();

            if (chunk && typeof chunk === "object" && !(chunk instanceof Blob) &&
                !(chunk instanceof ArrayBuffer) && !ArrayBuffer.isView(chunk)) {
              if (chunk.type === "seek") {
                position = Math.max(0, Number(chunk.position) || 0);
                return;
              }
              if (chunk.type === "truncate") {
                truncate(chunk.size);
                return;
              }
              if (chunk.type === "write") {
                if (Number.isFinite(chunk.position)) position = Math.max(0, chunk.position);
                chunk = chunk.data;
              }
            }

            const next = await toBytes(chunk);
            const end = position + next.length;
            const result = new Uint8Array(Math.max(bytes.length, end));
            result.set(bytes.subarray(0, position), 0);
            result.set(next, position);
            if (end < bytes.length) result.set(bytes.subarray(end), end);
            bytes = result;
            position = end;
          },

          async seek(offset) {
            ensureOpen();
            position = Math.max(0, Number(offset) || 0);
          },

          async truncate(size) {
            ensureOpen();
            truncate(size);
          },

          async close() {
            ensureOpen();
            await nativeCall("write", { uri, base64: encodeBase64(bytes) });
            closed = true;
          },

          async abort() {
            closed = true;
          }
        };
      }
    };
  }

  // The native header can preselect a folder; File > Open Folder consumes it once.
  window.__androidProvideDirectory = (uri, name) => {
    selectedDirectory = directoryHandle(uri, name || "Selected folder", []);
  };

  if (typeof window.showDirectoryPicker !== "function") {
    window.showDirectoryPicker = async () => {
      if (selectedDirectory) {
        const handle = selectedDirectory;
        selectedDirectory = null;
        return handle;
      }

      const root = await nativeCall("pickDirectory");
      return directoryHandle(root.uri, root.name, []);
    };
  }

  if (typeof window.showOpenFilePicker !== "function") {
    window.showOpenFilePicker = async (options = {}) => {
      const files = await nativeCall("pickFile", {
        multiple: Boolean(options.multiple),
        types: options.types || []
      });

      return files.map((file) =>
        fileHandle(file.uri, file.name, file.mimeType, [file.name])
      );
    };
  }
})();

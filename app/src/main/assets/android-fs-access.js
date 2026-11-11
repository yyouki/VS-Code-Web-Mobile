(() => {
  "use strict";

  // Android injects this object only into the trusted vscode.dev origin.
  if (!window.AndroidFs || window.__androidFsShimInstalled) return;
  window.__androidFsShimInstalled = true;

  // VS Code's HTMLFileSystemProvider checks native FileSystemFileHandle and
  // FileSystemDirectoryHandle instances. The Android bridge implements their
  // methods, so give bridge handles the matching prototype chain as well.
  if (typeof window.FileSystemHandle !== "function") {
    window.FileSystemHandle = class FileSystemHandle {};
  }
  if (typeof window.FileSystemFileHandle !== "function") {
    window.FileSystemFileHandle = class FileSystemFileHandle extends window.FileSystemHandle {};
  }
  if (typeof window.FileSystemDirectoryHandle !== "function") {
    window.FileSystemDirectoryHandle = class FileSystemDirectoryHandle extends window.FileSystemHandle {};
  }

  function withFileSystemPrototype(handle, kind) {
    const constructor = kind === "file"
      ? window.FileSystemFileHandle
      : window.FileSystemDirectoryHandle;
    try {
      Object.setPrototypeOf(handle, constructor.prototype);
    } catch (error) {
      throw new DOMException(
        "This WebView cannot expose a compatible Android file handle",
        "NotSupportedError"
      );
    }
    return handle;
  }

  const pending = new Map();
  let requestCounter = 0;
  let selectedDirectory = null;

  function nativeCall(method, args = {}) {
    return new Promise((resolve, reject) => {
      const id = String(++requestCounter);
      pending.set(id, { resolve, reject });
      try {
        window.AndroidFs.postMessage(JSON.stringify({ id, method, args }));
      } catch (error) {
        pending.delete(id);
        reject(error);
      }
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

  // Mirror modifier state from the on-screen or physical keyboard to the native key strip.
  function reportModifierState(event) {
    // Only modifier-key transitions update the toolbar. A Ctrl+A keyup still has ctrlKey=true;
    // treating every character event as a modifier update would leave CTRL latched afterward.
    const modifierKeys = new Set(["control", "alt", "shift", "meta"]);
    if (event && !modifierKeys.has(String(event.key || "").toLowerCase())) return;

    const state = {
      ctrl: Boolean(event && event.ctrlKey),
      alt: Boolean(event && event.altKey),
      shift: Boolean(event && event.shiftKey),
      meta: Boolean(event && event.metaKey)
    };
    window.AndroidFs.postMessage(JSON.stringify({
      id: "modifier-state",
      method: "modifierState",
      args: state
    }));
  }

  document.addEventListener("keydown", reportModifierState, true);
  document.addEventListener("keyup", reportModifierState, true);
  window.addEventListener("blur", () => {
    reportModifierState(null);
  });

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

  async function renameHandle(handle, newName) {
    safeName(newName);
    const result = await nativeCall("rename", { uri: handle._uri, name: newName });
    handle._uri = result.uri;
    handle.name = result.name;
    if (Array.isArray(handle._segments) && handle._segments.length > 0) {
      handle._segments = [...handle._segments.slice(0, -1), result.name];
    }
    return handle;
  }

  async function moveHandle(handle, destinationOrName, requestedName) {
    let destinationUri = handle._parentUri;
    let newName = handle.name;

    if (typeof destinationOrName === "string") {
      newName = destinationOrName;
    } else {
      if (!destinationOrName || destinationOrName.kind !== "directory") {
        throw new TypeError("Move destination must be a directory handle or a new name");
      }
      destinationUri = destinationOrName._uri;
      if (typeof requestedName === "string") newName = requestedName;
    }

    if (!handle._parentUri || !destinationUri) {
      throw new DOMException("This handle is not inside the selected workspace", "NotSupportedError");
    }

    safeName(newName);
    const result = await nativeCall("move", {
      uri: handle._uri,
      sourceParentUri: handle._parentUri,
      destinationUri,
      name: newName
    });
    handle._uri = result.uri;
    handle._parentUri = destinationUri;
    handle.name = result.name;
    const destinationSegments = typeof destinationOrName === "string"
      ? (handle._segments || []).slice(0, -1)
      : (destinationOrName._segments || []);
    handle._segments = [...destinationSegments, result.name];
    return handle;
  }

  function directoryHandle(uri, name, segments = [], parentUri = null) {
    const handle = {
      kind: "directory",
      name,
      _uri: uri,
      _parentUri: parentUri,
      _segments: segments,

      async queryPermission() { return "granted"; },
      async requestPermission() { return "granted"; },

      async isSameEntry(other) {
        return Boolean(other && other.kind === "directory" && other._uri === this._uri);
      },

      async getDirectoryHandle(childName, options = {}) {
        safeName(childName);
        let child = (await nativeCall("list", { uri: this._uri })).find((entry) => entry.name === childName);

        if (!child && options.create) {
          child = await nativeCall("createDirectory", { parentUri: this._uri, name: childName });
        }

        if (!child || !child.isDirectory) {
          throw new DOMException("Directory not found", "NotFoundError");
        }

        return directoryHandle(child.uri, child.name, [...this._segments, child.name], this._uri);
      },

      async getFileHandle(childName, options = {}) {
        safeName(childName);
        let child = (await nativeCall("list", { uri: this._uri })).find((entry) => entry.name === childName);

        if (!child && options.create) {
          child = await nativeCall("createFile", {
            parentUri: this._uri,
            name: childName,
            mimeType: "application/octet-stream"
          });
        }

        if (!child || child.isDirectory) {
          throw new DOMException("File not found", "NotFoundError");
        }

        return fileHandle(child.uri, child.name, child.mimeType, [...this._segments, child.name], this._uri);
      },

      async removeEntry(childName, options = {}) {
        safeName(childName);
        const child = (await nativeCall("list", { uri: this._uri })).find((entry) => entry.name === childName);
        if (!child) throw new DOMException("Entry not found", "NotFoundError");

        await nativeCall("delete", {
          uri: child.uri,
          recursive: Boolean(options.recursive)
        });
      },

      async resolve(possibleDescendant) {
        if (!possibleDescendant || !Array.isArray(possibleDescendant._segments)) return null;
        const ownSegments = this._segments;
        const child = possibleDescendant._segments;
        if (child.length < ownSegments.length) return null;

        for (let index = 0; index < ownSegments.length; index += 1) {
          if (ownSegments[index] !== child[index]) return null;
        }

        return child.slice(ownSegments.length);
      },

      async rename(newName) {
        return renameHandle(this, newName);
      },

      async move(destinationOrName, newName) {
        return moveHandle(this, destinationOrName, newName);
      },

      async *entries() {
        const parentUri = this._uri;
        const children = await nativeCall("list", { uri: parentUri });
        for (const child of children) {
          const childSegments = [...this._segments, child.name];
          const handle = child.isDirectory
            ? directoryHandle(child.uri, child.name, childSegments, parentUri)
            : fileHandle(child.uri, child.name, child.mimeType, childSegments, parentUri);
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
    return withFileSystemPrototype(handle, "directory");
  }

  function fileHandle(
    uri,
    name,
    mimeType = "application/octet-stream",
    segments = [],
    parentUri = null
  ) {
    const handle = {
      kind: "file",
      name,
      _uri: uri,
      _parentUri: parentUri,
      _segments: segments,

      async queryPermission() { return "granted"; },
      async requestPermission() { return "granted"; },

      async isSameEntry(other) {
        return Boolean(other && other.kind === "file" && other._uri === this._uri);
      },

      async rename(newName) {
        return renameHandle(this, newName);
      },

      async move(destinationOrName, newName) {
        return moveHandle(this, destinationOrName, newName);
      },

      async getFile() {
        const result = await nativeCall("read", { uri: this._uri });
        if (!result || typeof result.base64 !== "string") {
          throw new DOMException("Android did not return file contents", "NotReadableError");
        }

        // Return a real browser File so VS Code can inspect the bytes and metadata.
        return new File([decodeBase64(result.base64)], this.name, {
          type: result.mimeType || mimeType,
          lastModified: Number(result.lastModified) || Date.now()
        });
      },

      async createWritable(options = {}) {
        const fileUri = this._uri;
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
            await nativeCall("write", { uri: fileUri, base64: encodeBase64(bytes) });
            closed = true;
          },

          async abort() {
            closed = true;
          }
        };
      }
    };
    return withFileSystemPrototype(handle, "file");
  }

  // The native header can preselect a folder; File > Open Folder consumes it once.
  window.__androidProvideDirectory = (uri, name) => {
    selectedDirectory = directoryHandle(uri, name || "Selected folder", []);
  };

  // Always route the standard picker APIs to Android's Storage Access Framework.
  // Native WebView implementations are not guaranteed to expose a usable Android folder provider.
  window.showDirectoryPicker = async () => {
    if (selectedDirectory) {
      const handle = selectedDirectory;
      selectedDirectory = null;
      return handle;
    }

    const root = await nativeCall("pickDirectory");
    return directoryHandle(root.uri, root.name, []);
  };

  window.showOpenFilePicker = async (options = {}) => {
    const files = await nativeCall("pickFile", {
      multiple: Boolean(options.multiple),
      types: Array.isArray(options.types) ? options.types : []
    });

    if (!Array.isArray(files)) {
      throw new DOMException("Android returned an invalid file selection", "NotReadableError");
    }

    return files.map((file) =>
      fileHandle(file.uri, file.name, file.mimeType, [file.name])
    );
  };
})();

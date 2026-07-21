const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const https = require("node:https");
const os = require("node:os");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const pkg = require("../package.json");

const VERSION = pkg.version;
const DEFAULT_VERSIONS_URL = "https://jo-lang.org/versions.jsonl";
const CHECK_INSTALL_CONTEXT_ARG = "--check-install-context";

function installRoot() {
  return process.env.JO_INSTALL_ROOT || path.join(os.homedir(), ".jo");
}

function compilerDir() {
  return path.join(installRoot(), "compilers", VERSION);
}

function compilerBin() {
  return path.join(compilerDir(), "bin", "jo");
}

async function ensureInstalled(options = {}) {
  const quiet = options.quiet === true;

  ensureLocalNpmInstall();
  ensureSupportedPlatform();
  ensureCommand("java");
  ensureCommand("tar");

  if (isCompleteInstall()) {
    log(quiet, `Jo ${VERSION} is already installed at ${compilerDir()}`);
    return;
  }

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "jo-npm-install-"));

  try {
    const release = await resolveRelease();
    const tarball = path.join(tmp, `jo-${VERSION}.tar.gz`);

    log(quiet, `Installing Jo ${VERSION} to ${compilerDir()}`);
    log(quiet, `Downloading ${release.url}`);

    await copyOrDownload(release.url, tarball);

    if (release.sha256) {
      verifySha256(tarball, release.sha256);
    } else {
      log(quiet, "Skipping SHA256 verification for local tarball");
    }

    extractTarball(tarball, tmp);
    installExtractedCompiler(tmp, quiet);

    log(quiet, `Jo ${VERSION} installed successfully`);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

async function resolveRelease() {
  const tarball = process.env.JO_NPM_TARBALL;
  if (tarball) {
    return {
      url: tarball,
      sha256: process.env.JO_NPM_SHA256 || null,
    };
  }

  const versionsUrl = process.env.JO_VERSIONS_URL || DEFAULT_VERSIONS_URL;
  const content = await fetchText(versionsUrl);
  const record = parseVersionRecords(content).find(item => item.version === VERSION);

  if (!record) {
    throw new Error(`Jo ${VERSION} was not found in ${versionsUrl}`);
  }

  const sha256Text = await fetchText(record.sha256url);
  const sha256 = sha256Text.trim().split(/\s+/)[0];
  if (!sha256) {
    throw new Error(`Could not read SHA256 checksum from ${record.sha256url}`);
  }

  return {
    url: record.url,
    sha256,
  };
}

function parseVersionRecords(content) {
  return content
    .split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line.length > 0 && !line.startsWith("//"))
    .map(line => JSON.parse(line));
}

function isCompleteInstall() {
  return (
    isExecutable(compilerBin()) &&
    fs.existsSync(path.join(compilerDir(), "bin", "jo.jar")) &&
    fs.statSync(path.join(compilerDir(), "libs"), { throwIfNoEntry: false })?.isDirectory()
  );
}

function installExtractedCompiler(tmp, quiet) {
  const extracted = path.join(tmp, `jo-${VERSION}`);
  if (!fs.statSync(extracted, { throwIfNoEntry: false })?.isDirectory()) {
    throw new Error(`Unexpected tarball layout: missing jo-${VERSION}/ directory`);
  }

  const target = compilerDir();
  const targetParent = path.dirname(target);

  if (fs.existsSync(target)) {
    log(quiet, `Replacing incomplete installation at ${target}`);
    fs.rmSync(target, { recursive: true, force: true });
  }

  fs.mkdirSync(targetParent, { recursive: true });

  try {
    fs.renameSync(extracted, target);
  } catch (error) {
    if (error.code !== "EXDEV") {
      throw error;
    }
    fs.cpSync(extracted, target, { recursive: true });
    fs.rmSync(extracted, { recursive: true, force: true });
  }

  fs.chmodSync(compilerBin(), 0o755);

  if (!isCompleteInstall()) {
    throw new Error(`Installed Jo ${VERSION}, but the compiler layout is incomplete`);
  }
}

function verifySha256(file, expected) {
  const actual = crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
  if (actual.toLowerCase() !== expected.toLowerCase()) {
    throw new Error(`SHA256 mismatch for ${path.basename(file)}: expected ${expected}, got ${actual}`);
  }
}

function extractTarball(tarball, dest) {
  const result = spawnSync("tar", ["-xzf", tarball, "-C", dest], {
    encoding: "utf8",
  });

  if (result.status !== 0) {
    const output = [result.stdout, result.stderr].filter(Boolean).join("\n").trim();
    throw new Error(`tar extraction failed with exit ${result.status}${output ? `: ${output}` : ""}`);
  }
}

async function copyOrDownload(source, dest) {
  if (/^https?:\/\//.test(source)) {
    await downloadFile(source, dest);
  } else {
    fs.copyFileSync(path.resolve(source), dest);
  }
}

function fetchText(url) {
  return requestUrl(url, (response, resolve, reject) => {
    let data = "";
    response.setEncoding("utf8");
    response.on("data", chunk => {
      data += chunk;
    });
    response.on("end", () => resolve(data));
    response.on("error", reject);
  });
}

function downloadFile(url, dest) {
  return requestUrl(url, (response, resolve, reject) => {
    const file = fs.createWriteStream(dest);
    response.pipe(file);
    file.on("finish", () => file.close(resolve));
    file.on("error", reject);
    response.on("error", reject);
  });
}

function requestUrl(url, handler, redirects = 0) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const client = parsed.protocol === "http:" ? http : https;

    const request = client.get(
      parsed,
      {
        headers: {
          "user-agent": `${pkg.name}/${VERSION}`,
        },
      },
      response => {
        const location = response.headers.location;
        if (response.statusCode >= 300 && response.statusCode < 400 && location) {
          response.resume();
          if (redirects >= 10) {
            reject(new Error(`Too many redirects fetching ${url}`));
            return;
          }
          resolve(requestUrl(new URL(location, url).toString(), handler, redirects + 1));
          return;
        }

        if (response.statusCode !== 200) {
          response.resume();
          reject(new Error(`HTTP ${response.statusCode} fetching ${url}`));
          return;
        }

        handler(response, resolve, reject);
      },
    );

    request.on("error", reject);
  });
}

function ensureSupportedPlatform() {
  if (process.platform !== "darwin" && process.platform !== "linux") {
    throw new Error("Jo currently supports Linux and macOS only");
  }
}

function ensureCommand(command) {
  if (!findOnPath(command)) {
    throw new Error(`'${command}' is required but was not found on PATH`);
  }
}

function findOnPath(command) {
  const pathValue = process.env.PATH || "";
  for (const dir of pathValue.split(path.delimiter)) {
    if (!dir) {
      continue;
    }

    const candidate = path.join(dir, command);
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      return candidate;
    } catch (_) {
      // Keep searching PATH.
    }
  }
  return null;
}

function isExecutable(file) {
  try {
    fs.accessSync(file, fs.constants.X_OK);
    return true;
  } catch (_) {
    return false;
  }
}

function log(quiet, message) {
  if (!quiet) {
    console.error(message);
  }
}

function ensureLocalNpmInstall() {
  if (!process.env.npm_lifecycle_event) {
    return;
  }

  if (!isGlobalNpmInstall()) {
    return;
  }

  throw new Error(
    `${pkg.name} does not support global installation. Install it in a project with ` +
      "`npm install --save-dev @typescope/jo`, then run Jo through `npx --no-install jo` " +
      "or a package.json script.",
  );
}

function isGlobalNpmInstall() {
  return (
    process.env.npm_config_global === "true" ||
    process.env.npm_config_location === "global"
  );
}

if (require.main === module) {
  if (process.argv.includes(CHECK_INSTALL_CONTEXT_ARG)) {
    try {
      ensureLocalNpmInstall();
    } catch (error) {
      console.error(`error: ${error.message}`);
      process.exit(1);
    }
    process.exit(0);
  }

  ensureInstalled().catch(error => {
    console.error(`error: ${error.message}`);
    process.exit(1);
  });
}

module.exports = {
  VERSION,
  compilerBin,
  compilerDir,
  ensureInstalled,
  installRoot,
};

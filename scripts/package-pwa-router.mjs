import { chmodSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, readlinkSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const release = process.argv[2];
const architecture = process.argv[3] ?? "aarch64";
const targets = {
  aarch64: { triple: "aarch64-unknown-linux-musl", machine: 183 },
  x86_64: { triple: "x86_64-unknown-linux-musl", machine: 62 },
};
if (!release || !/^[a-z0-9][a-z0-9-]{0,63}$/.test(release)) {
  throw new Error("Usage: node scripts/package-pwa-router.mjs <release-name> [aarch64|x86_64]");
}
if (!Object.hasOwn(targets, architecture)) throw new Error("Unsupported Linux architecture");
const target = targets[architecture];
const build = path.join(root, ".next-router");
const bridge = path.join(root, ".pwa-data/router-cargo", target.triple, "release/pipo_pwa_bridge");
const header = readFileSync(bridge).subarray(0, 20);
if (header.toString("hex", 0, 4) !== "7f454c46" || header[4] !== 2 || header[5] !== 1 || header.readUInt16LE(18) !== target.machine) {
  throw new Error(`Bridge must be a Linux ${architecture} ELF executable`);
}
const destination = path.join(root, ".pwa-data/router-releases", release);
if (existsSync(destination)) throw new Error(`Release already exists: ${destination}`);
const app = path.join(destination, "app");
mkdirSync(destination, { recursive: true });
cpSync(path.join(build, "standalone"), app, {
  recursive: true,
  verbatimSymlinks: true,
  filter: source => !source.split(path.sep).some(part => part === ".pwa-data" || part === ".env" || part.startsWith(".env.")),
});
function verifyLinks(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const filename = path.join(directory, entry.name);
    if (entry.isSymbolicLink()) {
      const target = readlinkSync(filename);
      const resolved = path.resolve(directory, target);
      if (path.isAbsolute(target) || !resolved.startsWith(`${app}${path.sep}`)) {
        throw new Error(`Dependency link escapes deployment: ${filename}`);
      }
    } else if (entry.isDirectory()) verifyLinks(filename);
  }
}
verifyLinks(app);
cpSync(path.join(build, "static"), path.join(app, ".next-router/static"), { recursive: true });
cpSync(path.join(root, "public"), path.join(app, "public"), { recursive: true });
mkdirSync(path.join(app, "bin"));
cpSync(bridge, path.join(app, "bin/pipo_pwa_bridge"));
chmodSync(path.join(app, "bin/pipo_pwa_bridge"), 0o755);
cpSync(path.join(root, "deploy/pwa/Dockerfile"), path.join(destination, "Dockerfile"));
execFileSync("tar", ["-czf", `${destination}.tar.gz`, "-C", destination, "."], {
  env: { ...process.env, COPYFILE_DISABLE: "1" },
});
console.log(`${destination}.tar.gz`);

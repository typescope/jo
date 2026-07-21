#!/usr/bin/env node

const { spawn } = require("node:child_process");
const { compilerBin, ensureInstalled, VERSION } = require("../scripts/install");

async function main() {
  try {
    await ensureInstalled({ quiet: true });
  } catch (error) {
    console.error(`error: could not install Jo ${VERSION}: ${error.message}`);
    process.exit(1);
  }

  const child = spawn(compilerBin(), process.argv.slice(2), {
    stdio: "inherit",
    env: process.env,
  });

  child.on("error", error => {
    console.error(`error: could not run Jo ${VERSION}: ${error.message}`);
    process.exit(1);
  });

  child.on("exit", (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code === null ? 1 : code);
  });
}

main();

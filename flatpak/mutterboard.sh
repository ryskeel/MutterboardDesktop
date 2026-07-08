#!/bin/sh
# jpackage launchers resolve their runtime relative to the real binary, so
# exec the binary directly rather than symlinking it into /app/bin.
exec /app/Mutterboard/bin/Mutterboard "$@"

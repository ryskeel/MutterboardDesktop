#!/bin/sh
# The desktop's cursor theme lives on the host, outside the sandbox's icon
# path; without it libXcursor falls back to the small unscaled core cursor.
# Theme and size themselves come from the X resources (Xcursor.theme/size).
export XCURSOR_PATH="${XCURSOR_PATH:+$XCURSOR_PATH:}$HOME/.icons:/run/host/user-share/icons:/run/host/share/icons:/usr/share/icons"
# jpackage launchers resolve their runtime relative to the real binary, so
# exec the binary directly rather than symlinking it into /app/bin.
exec /app/Mutterboard/bin/Mutterboard "$@"

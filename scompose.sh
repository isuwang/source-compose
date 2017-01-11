#!/usr/bin/env sh
exec java -jar -XX:+UseG1GC "$0" "$@"

#!/usr/bin/env bash
# Prepares a Paper test server for e2e.mjs: downloads Paper and PacketEvents,
# writes a superflat offline-mode config and turns the shield on.
# Usage: ci-setup.sh <server dir> <plugin jar> [mc version] [packetevents tag] [via yes|no]
set -euo pipefail

DIR=${1:?server dir}
JAR=${2:?plugin jar}
MC=${3:-1.21.11}
PE_TAG=${4:-v2.13.0}
VIA=${5:-no}
UA="outofsight-ci (https://github.com/tunaseckin/outofsight)"

mkdir -p "$DIR/plugins/OutOfSight"

# Paper: the Fill v3 API, falling back to the older v2 API.
url=$(curl -fsSL -A "$UA" "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds/latest" \
      | jq -r '.downloads."server:default".url // empty' || true)
if [ -z "$url" ]; then
  build=$(curl -fsSL -A "$UA" "https://api.papermc.io/v2/projects/paper/versions/$MC/builds" \
          | jq -r '.builds[-1].build')
  name=$(curl -fsSL -A "$UA" "https://api.papermc.io/v2/projects/paper/versions/$MC/builds/$build" \
          | jq -r '.downloads.application.name')
  url="https://api.papermc.io/v2/projects/paper/versions/$MC/builds/$build/downloads/$name"
fi
echo "Paper: $url"
curl -fsSL -A "$UA" -o "$DIR/paper.jar" "$url"

# Downloads one plugin jar from a GitHub release (latest when TAG is empty),
# preferring an asset whose name matches PREFER and skipping sources/javadoc.
fetch_jar() {
  local repo=$1 tag=$2 prefer=$3 tmp
  tmp=$(mktemp -d)
  gh release download ${tag:+"$tag"} -R "$repo" -p '*.jar' -D "$tmp"
  local pick
  pick=$(ls "$tmp"/*.jar | grep -viE 'sources|javadoc' | grep -iE "$prefer" | head -n1 || true)
  [ -n "$pick" ] || pick=$(ls "$tmp"/*.jar | grep -viE 'sources|javadoc' | head -n1)
  echo "$repo ${tag:-latest}: $(basename "$pick")"
  cp "$pick" "$DIR/plugins/"
}

fetch_jar retrooper/packetevents "$PE_TAG" 'spigot|paper|bukkit'
if [ "$VIA" = "yes" ]; then
  fetch_jar ViaVersion/ViaVersion "" 'viaversion'
  fetch_jar ViaVersion/ViaBackwards "" 'viabackwards'
fi
ls -l "$DIR/plugins"

cp "$JAR" "$DIR/plugins/"

echo "eula=true" > "$DIR/eula.txt"
cat > "$DIR/server.properties" <<PROPS
online-mode=false
level-type=minecraft\:flat
generate-structures=false
difficulty=peaceful
spawn-monsters=false
view-distance=6
simulation-distance=4
spawn-protection=0
enable-command-block=false
PROPS

# The plugin's own default config with the shield switched on, and decoys in
# every chunk with an alert on the first honeypot hit, so both can be checked.
sed -e '0,/^  enabled: false/s//  enabled: true/' \
    -e 's/^  decoys-per-chunk: 0/  decoys-per-chunk: 1/' \
    -e 's/^  decoy-chunk-interval: 4/  decoy-chunk-interval: 1/' \
    -e 's/^    threshold: 2/    threshold: 1/' \
    plugin/src/main/resources/config.yml > "$DIR/plugins/OutOfSight/config.yml"
grep -nE "^  enabled:|decoys-per-chunk|decoy-chunk-interval|    threshold" "$DIR/plugins/OutOfSight/config.yml"

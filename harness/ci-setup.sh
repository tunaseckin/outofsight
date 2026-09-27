#!/usr/bin/env bash
# Prepares a Paper test server for e2e.mjs: downloads Paper and PacketEvents,
# writes a superflat offline-mode config and turns the shield on.
# Usage: ci-setup.sh <server dir> <plugin jar>
set -euo pipefail

DIR=${1:?server dir}
JAR=${2:?plugin jar}
MC=1.21.11
PE_TAG=v2.13.0
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

# PacketEvents: the Spigot/Paper jar from the matching GitHub release.
gh release download "$PE_TAG" -R retrooper/packetevents -p '*spigot*.jar' -D "$DIR/plugins"
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

# The plugin's own default config with the shield switched on.
sed '0,/^  enabled: false/s//  enabled: true/' plugin/src/main/resources/config.yml \
  > "$DIR/plugins/OutOfSight/config.yml"
grep -n "^  enabled:" "$DIR/plugins/OutOfSight/config.yml"

# Bakes the Forge + Minecraft server libraries into a shared image layer so server-a and
# server-b get them once at build time instead of each downloading them independently on first
# boot. At runtime itzg's start-deployForge re-runs the same install-forge, finds the baked
# install via /data/.forge-manifest.json, and skips the download.
#
# The base image is pinned by digest so a repush of the moving `java17` tag cannot drift
# the test. Re-resolve with:
#   docker buildx imagetools inspect itzg/minecraft-server:java17
FROM itzg/minecraft-server:java17@sha256:c3af905ce625d8626f389e67dd35c16d49d44f78154dd27c18f3213aa33df8b8

# These must match the VERSION/FORGE_VERSION the servers run in docker-compose.yml. A
# mismatch only costs the cache, because itzg then re-downloads Forge at boot instead of
# failing the run.
ARG VERSION=1.20.1
ARG FORGE_VERSION=47.4.0

# The build runs as root, so hand /data to the minecraft user that itzg drops to at
# startup, which must be able to rewrite .run-forge.env. The chown shares one RUN
# instruction with the install so /data is not duplicated across two image layers.
RUN mc-image-helper install-forge \
      --output-directory=/data \
      --results-file=/data/.run-forge.env \
      --minecraft-version="${VERSION}" \
      --forge-version="${FORGE_VERSION}" \
 && chown -R minecraft:minecraft /data

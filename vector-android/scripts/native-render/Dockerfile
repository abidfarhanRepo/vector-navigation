FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
# Ubuntu 24.04 gives ICU 74 + libjpeg8, which the MapLibre Native prebuilt needs
# and Fedora 44 does not provide. But its Node is 18 (ABI 109) and no prebuilt
# exists for that — the published binaries are ABI 127 (Node 22), so NodeSource.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
      curl ca-certificates gnupg libjpeg-turbo8 libicu74 libegl1 libgles2 \
      libopengl0 libglx0 libx11-6 libcurl4 libuv1 xvfb libwebp7 libpng16-16 libtiff6 \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null 2>&1 \
    && apt-get install -y -qq nodejs \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
RUN node -v && npm init -y >/dev/null 2>&1 && npm install @maplibre/maplibre-gl-native 2>&1 | tail -5

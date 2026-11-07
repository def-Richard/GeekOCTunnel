# Build/run tests as an ordinary container user. Package installation is confined to the image.
ARG BASE_IMAGE=ubuntu:24.04
FROM ${BASE_IMAGE}
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential cmake ninja-build qt6-base-dev libopenconnect-dev iproute2 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home builder
COPY --chown=builder:builder desktop /work/desktop
COPY --chown=builder:builder contracts /work/contracts
USER builder
WORKDIR /work
RUN cmake -S desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --parallel 2
ENV QT_QPA_PLATFORM=offscreen
CMD ["sh", "-c", "ctest --test-dir build --output-on-failure && build/geekoctunnel-probe"]

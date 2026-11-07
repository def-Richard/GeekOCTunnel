# Compile the Web UI and native host without installing anything on the host OS.
ARG NODE_IMAGE=node:24-bookworm-slim
ARG RUST_IMAGE=rust:1.99.1-slim-bookworm
FROM ${NODE_IMAGE} AS web
WORKDIR /work
COPY desktop/frontend/package*.json ./
RUN npm ci
COPY desktop/frontend/index.html ./
COPY desktop/frontend/src ./src
COPY desktop/frontend/public ./public
COPY desktop/frontend/vitest.config.js ./
COPY desktop/frontend/tests/ui.test.js ./tests/ui.test.js
RUN npm test && npm run build

FROM ${RUST_IMAGE} AS desktop
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    build-essential cmake ninja-build qt6-base-dev libopenconnect-dev pkg-config \
    libgtk-3-dev libwebkit2gtk-4.1-dev libssl-dev librsvg2-dev ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home builder && mkdir /work && chown builder:builder /work
COPY --chown=builder:builder desktop /work/desktop
COPY --chown=builder:builder contracts /work/contracts
COPY --from=web --chown=builder:builder /work/dist /work/desktop/frontend/dist
USER builder
WORKDIR /work
RUN cmake -S desktop -B build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --parallel 2 \
    && ctest --test-dir build --output-on-failure \
    && mkdir -p desktop/frontend/src-tauri/resources/native \
    && cp build/geekoctunnel-bridge desktop/frontend/src-tauri/resources/native/
WORKDIR /work/desktop/frontend/src-tauri
RUN cargo build --release --locked --features tauri/custom-protocol -j 2 \
    && cargo test --release --locked --features tauri/custom-protocol -j 2
CMD ["cargo", "test", "--release", "--locked", "--features", "tauri/custom-protocol", "--offline"]

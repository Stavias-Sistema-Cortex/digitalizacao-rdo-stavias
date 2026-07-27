export type ApiProxyEnvironment = {
  CORTEX_API_ORIGIN: string;
};

export type FetchLike = (request: Request) => Promise<Response>;

function configuredOrigin(value: string): string {
  const candidate = value?.trim();
  if (!candidate) {
    throw new Error("CORTEX_API_ORIGIN não configurada.");
  }
  const url = new URL(candidate);
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error("CORTEX_API_ORIGIN inválida.");
  }
  return url.origin;
}

export async function proxyApiRequest(
  request: Request,
  environment: ApiProxyEnvironment,
  fetchImpl: FetchLike = fetch,
): Promise<Response> {
  const source = new URL(request.url);
  if (!source.pathname.startsWith("/api/")) {
    return new Response("Not found", { status: 404 });
  }
  const target = new URL(source.pathname + source.search, configuredOrigin(
    environment.CORTEX_API_ORIGIN,
  ));
  const upstream = await fetchImpl(new Request(target, request));
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: upstream.headers,
  });
}

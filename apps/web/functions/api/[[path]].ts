import {
  proxyApiRequest,
  type ApiProxyEnvironment,
} from "../../src/lib/deploy/pagesApiProxy";

export const onRequest: PagesFunction<ApiProxyEnvironment> = (context) =>
  proxyApiRequest(context.request, context.env);

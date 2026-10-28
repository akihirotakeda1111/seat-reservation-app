import { pathToFileURL } from "node:url";

export function validate(env) {
  const required = (key) => {
    const value = env[key];
    if (!value?.trim() || value !== value.trim())
      throw new Error(`Set ${key} without surrounding whitespace`);
    return value;
  };
  required("VITE_COGNITO_CLIENT_ID");
  const urls = Object.fromEntries(
    [
      "VITE_COGNITO_AUTHORITY",
      "VITE_COGNITO_DOMAIN",
      "VITE_AUTH_REDIRECT_URI",
      "VITE_AUTH_LOGOUT_URI",
    ].map((key) => {
      const url = new URL(required(key));
      if (
        url.protocol !== "https:" ||
        url.username ||
        url.password ||
        url.search ||
        url.hash
      ) {
        throw new Error(
          `${key} must be an HTTPS URL without credentials, query or fragment`,
        );
      }
      return [key, url];
    }),
  );
  const redirect = urls.VITE_AUTH_REDIRECT_URI;
  const logout = urls.VITE_AUTH_LOGOUT_URI;
  if (
    redirect.origin !== logout.origin ||
    redirect.pathname !== "/auth/callback" ||
    logout.pathname !== "/login"
  ) {
    throw new Error(
      "Redirect and logout must share an origin and use /auth/callback and /login",
    );
  }
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  validate(process.env);
}

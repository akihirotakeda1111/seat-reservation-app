import { test } from "node:test";
import assert from "node:assert/strict";
import { validate } from "./validate-frontend-env.mjs";

const valid = {
  VITE_COGNITO_AUTHORITY:
    "https://cognito-idp.ap-northeast-1.amazonaws.com/example",
  VITE_COGNITO_CLIENT_ID: "client",
  VITE_COGNITO_DOMAIN: "https://example.auth.ap-northeast-1.amazoncognito.com",
  VITE_AUTH_REDIRECT_URI: "https://app.example.com/auth/callback",
  VITE_AUTH_LOGOUT_URI: "https://app.example.com/login",
};
test("accepts valid release configuration", () =>
  assert.doesNotThrow(() => validate(valid)));
test("rejects missing configuration", () => {
  for (const key of Object.keys(valid))
    assert.throws(() => validate({ ...valid, [key]: "" }));
});
test("rejects unsafe or inconsistent callback URLs", () => {
  for (const url of [
    "http://app.example.com/login",
    "https://other.example.com/login",
    "https://app.example.com/other",
    "https://app.example.com/login?query=1",
    "https://user:password@app.example.com/login",
    " https://app.example.com/login",
  ]) {
    assert.throws(() => validate({ ...valid, VITE_AUTH_LOGOUT_URI: url }));
  }
});

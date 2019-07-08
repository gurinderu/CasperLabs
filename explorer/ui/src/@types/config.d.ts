// Declared so we can get it from the `window` object.
interface Window {
  origin: string;
  config: Config;
}

interface Config {
  auth0: Auth0Config;
}

interface Auth0Config {
  domain: string;
  clientId: string;
}

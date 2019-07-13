import { observable } from 'mobx';
import * as nacl from 'tweetnacl-ts';
import { saveAs } from 'file-saver';
import ErrorContainer from './ErrorContainer';
import FormData from './FormData';
import AuthService from '../services/AuthService';
import { encodeBase64 } from '../lib/Conversions';

// https://www.npmjs.com/package/tweetnacl-ts#signatures
// https://tweetnacl.js.org/#/sign

export class AuthContainer {
  @observable user: User | null = null;
  @observable accounts: UserAccount[] | null = null;

  // An account we are creating, while we're configuring it.
  @observable newAccount: NewAccountFormData | null = null;

  @observable selectedAccount: UserAccount | null = null;

  constructor(
    private errors: ErrorContainer,
    private authService: AuthService
  ) {
    this.init();
  }

  private async init() {
    if (window.location.search.includes('code=')) {
      const { appState } = await this.authService.handleRedirectCallback();
      const url =
        appState && appState.targetUrl
          ? appState.targetUrl
          : window.location.pathname;
      window.history.replaceState({}, document.title, url);
    }

    this.fetchUser();
  }

  async login() {
    const isAuthenticated = await this.authService.isAuthenticated();
    if (!isAuthenticated) {
      await this.authService.login();
    }
    this.fetchUser();
  }

  async logout() {
    this.user = null;
    this.accounts = null;
    sessionStorage.clear();
    this.authService.logout();
  }

  private async fetchUser() {
    const isAuthenticated = await this.authService.isAuthenticated();
    this.user = isAuthenticated ? await this.authService.getUser() : null;
    this.refreshAccounts();
  }

  async refreshAccounts() {
    if (this.user != null) {
      const meta: UserMetadata = await this.authService.getUserMetadata(
        this.user.sub
      );
      this.accounts = meta.accounts || [];
    }
  }

  // Open a new account creation form.
  configureNewAccount() {
    this.newAccount = new NewAccountFormData(this.accounts!);
  }

  async createAccount(): Promise<boolean> {
    let form = this.newAccount!;
    if (form.clean()) {
      // Save the private and public keys to disk.
      saveToFile(form.privateKeyBase64, `${form.name}.private.key`);
      saveToFile(form.publicKeyBase64, `${form.name}.public.key`);
      // Add the public key to the accounts and save it to Auth0.
      await this.addAccount({
        name: form.name,
        publicKeyBase64: form.publicKeyBase64
      });
      return true;
    } else {
      return false;
    }
  }

  deleteAccount(name: String) {
    if (window.confirm(`Are you sure you want to delete account '${name}'?`)) {
      this.accounts = this.accounts!.filter(x => x.name !== name);
      this.errors.capture(this.saveAccounts());
    }
  }

  private addAccount(account: UserAccount) {
    this.accounts!.push(account);
    this.errors.capture(this.saveAccounts());
  }

  private async saveAccounts() {
    await this.authService.updateUserMetadata(this.user!.sub, {
      accounts: this.accounts || undefined
    });
  }

  selectAccountByName(name: string) {
    this.selectedAccount = this.accounts!.find(x => x.name === name) || null;
  }
}

function saveToFile(content: string, filename: string) {
  let blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  saveAs(blob, filename);
}

class NewAccountFormData extends FormData {
  constructor(private accounts: UserAccount[]) {
    super();
    // Generate key pair and assign to public and private keys.
    const keys = nacl.sign_keyPair();
    this.publicKeyBase64 = encodeBase64(keys.publicKey);
    this.privateKeyBase64 = encodeBase64(keys.secretKey);
  }

  @observable name: string = '';
  @observable publicKeyBase64: string = '';
  @observable privateKeyBase64: string = '';

  protected check() {
    if (this.name === '') return 'Name cannot be empty!';

    if (this.accounts.some(x => x.name === this.name))
      return `An account with name '${this.name}' already exists.`;

    if (this.accounts.some(x => x.publicKeyBase64 === this.publicKeyBase64))
      return 'An account with this public key already exists.';

    return null;
  }
}

export default AuthContainer;

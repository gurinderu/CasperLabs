import React from 'react';
import ReactDOM from 'react-dom';
import { HashRouter } from 'react-router-dom';

import * as serviceWorker from './serviceWorker';
import App from './components/App';

import 'bootstrap/dist/css/bootstrap.min.css';
import '@fortawesome/fontawesome-free/css/all.min.css';
// https://startbootstrap.com/template-overviews/sb-admin/
import './styles/sb-admin/sb-admin.scss';
import './styles/custom.scss';

// Make `jQuery` available in the window in case any Javascript we import directly uses it.
import * as jQuery from 'jquery';

import CasperContainer from './containers/CasperContainer';
import AuthContainer from './containers/AuthContainer';
import ErrorContainer from './containers/ErrorContainer';
import FaucetService from './services/FaucetService';
import Auth0Service from './services/Auth0Service';

let w = window as any;
w.$ = w.jQuery = jQuery;

const auth0Service = new Auth0Service(window.config.auth0);
const faucetService = new FaucetService(auth0Service);

const errors = new ErrorContainer();
const casper = new CasperContainer(errors, faucetService);
const auth = new AuthContainer(errors, auth0Service);

ReactDOM.render(
  <HashRouter>
    <App casper={casper} auth={auth} errors={errors} />
  </HashRouter>,
  document.getElementById('root')
);

// If you want your app to work offline and load faster, you can change
// unregister() to register() below. Note this comes with some pitfalls.
// Learn more about service workers: https://bit.ly/CRA-PWA
serviceWorker.unregister();

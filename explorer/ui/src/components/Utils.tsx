import React from 'react';
import { Route, RouteProps } from 'react-router-dom';
import { observer } from 'mobx-react';
import AuthContainer from '../containers/AuthContainer';

export const Spinner = (msg: String) => (
  <div className="text-center">
    <i className="fa fa-fw fa-spin fa-spinner" />
    {msg}...
  </div>
);

export const Loading = () => Spinner('Loading');

export const Icon = (props: { name: string; color?: string }) => {
  const styles = {
    color: props.color
  };
  return <i className={'fa fa-fw fa-' + props.name} style={styles} />;
};

export const IconButton = (props: {
  onClick: () => void;
  title: string;
  icon: string;
}) => (
  <a onClick={_ => props.onClick()} title={props.title} className="icon-button">
    <Icon name={props.icon} />
  </a>
);

export const RefreshButton = (props: { refresh: () => void }) => (
  <IconButton onClick={() => props.refresh()} title="Refresh" icon="redo" />
);

export const Button = (props: {
  onClick: () => void;
  title: string;
  disabled?: boolean;
}) => (
  <button
    type="button"
    onClick={_ => props.onClick()}
    className="btn btn-primary"
    disabled={props.disabled || false}
  >
    {props.title}
  </button>
);

export const ListInline = (props: { children: any }) => {
  const children = [].concat(props.children);
  return (
    <ul className="list-inline">
      {children.map((child: any, idx: number) => (
        <li key={idx} className="list-inline-item">
          {child}
        </li>
      ))}
    </ul>
  );
};

// RefreshableComponent calls it's `refresh()` when it
// has mounted where it should get data from the server.
// It should either then use `setState` or wait for MobX
// to notify it of any changes. We can also call this
// method from the callback of a refresh button, or
// add a method here to start a timer which should be
// stopped in `componentWillUnmount`.
export abstract class RefreshableComponent<P, S> extends React.Component<P, S> {
  abstract refresh(): void;

  protected refreshIntervalMillis: number = 0;
  protected timerId: number = 0;

  // See all lifecycle methods at https://reactjs.org/docs/react-component.html
  componentDidMount() {
    this.refresh();
    if (this.refreshIntervalMillis > 0) {
      this.timerId = window.setInterval(
        () => this.refresh(),
        this.refreshIntervalMillis
      );
    }
  }

  componentWillUnmount() {
    if (this.timerId !== 0) {
      window.clearInterval(this.timerId);
    }
  }
}

export const UnderConstruction = (props: { children: any }) => {
  return (
    <div className="card shadow mb-3">
      <div className="card-header bg-warning">
        <h4 className="card-title font-weight-bold text-white">
          Under construction
        </h4>
      </div>
      <div className="card-body">{props.children}</div>
    </div>
  );
};

export const CommandLineHint = (props: { children: any }) => {
  return (
    <div className="card shadow mb-3">
      <div className="card-header bg-info">
        <h5 className="card-title font-weight-bold text-white">
          <Icon name="terminal" />
        </h5>
      </div>
      <div className="card-body">{props.children}</div>
    </div>
  );
};

interface PrivateRouteProps extends RouteProps {
  auth: AuthContainer;
}

@observer
export class PrivateRoute extends React.Component<PrivateRouteProps, {}> {
  render() {
    if (this.props.auth.user == null) {
      this.props.auth.login();
      return Spinner('Logging in');
    }
    return <Route {...this.props} />;
  }
}

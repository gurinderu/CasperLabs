import * as React from 'react';

import { RefreshButton, Loading } from './Utils';

export interface Props<T> {
  title: string;
  refresh?: () => void;
  headers: string[];
  rows: T[] | null;
  emptyMessage?: any;
  renderRow: (x: T, idx: number) => any;
  renderHeader?: (x: string) => any;
  footerMessage?: any;
}

export default class DataTable<T> extends React.Component<Props<T>> {
  render() {
    return (
      <div className="card mb-3">
        <div className="card-header">
          <span>{this.props.title}</span>
          {this.props.refresh && (
            <div className="float-right">
              <RefreshButton refresh={() => this.props.refresh!()} />
            </div>
          )}
        </div>
        <div className="card-body">
          {this.props.rows == null ? (
            <Loading />
          ) : this.props.rows.length === 0 ? (
            <div className="small text-muted">
              {this.props.emptyMessage || 'No data to show.'}
            </div>
          ) : (
            <table className="table table-bordered">
              <thead>
                <tr>
                  {this.props.headers.map(label =>
                    this.props.renderHeader ? (
                      this.props.renderHeader(label)
                    ) : (
                      <th key={label}>{label}</th>
                    )
                  )}
                </tr>
              </thead>
              <tbody>{this.props.rows.map(this.props.renderRow)}</tbody>
            </table>
          )}
        </div>
        {this.props.footerMessage && (
          <div className="card-footer small text-muted">
            {this.props.footerMessage}
          </div>
        )}
      </div>
    );
  }
}

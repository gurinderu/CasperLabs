import React from 'react';
import { observer } from 'mobx-react';
import { Form, SelectField, TextField } from './Forms';
import AuthContainer from '../containers/AuthContainer';
import { CasperContainer, FaucetRequest } from '../containers/CasperContainer';
import { RefreshableComponent, Button, CommandLineHint, Icon } from './Utils';
import DataTable from './DataTable';
import { base64to16, encodeBase16 } from '../lib/Conversions';
import { DeployInfo } from '../grpc/io/casperlabs/casper/consensus/info_pb';

interface Props {
  auth: AuthContainer;
  casper: CasperContainer;
}

@observer
class Faucet extends RefreshableComponent<Props, {}> {
  refresh() {
    this.props.auth.refreshAccounts();
    this.props.casper.refreshFaucetRequestStatus();
  }

  render() {
    const { auth, casper } = this.props;
    return (
      <div>
        <FaucetForm auth={auth} requestTokens={x => casper.requestTokens(x)} />
        <StatusTable
          requests={casper.faucetRequests}
          onRefresh={() => this.props.casper.refreshFaucetRequestStatus()}
        />
        <CliHint requests={casper.faucetRequests} />
      </div>
    );
  }
}

const FaucetForm = observer(
  (props: {
    auth: AuthContainer;
    requestTokens: (account: UserAccount) => void;
  }) => {
    const auth = props.auth;

    return (
      <div className="card mb-3">
        <div className="card-header">
          <span>Faucet</span>
        </div>
        <div className="card-body">
          <Form>
            <SelectField
              id="id-account-name"
              label="Account"
              placeholder="Select account"
              value={
                (auth.selectedAccount && auth.selectedAccount.name) || null
              }
              options={(auth.accounts || []).map(x => ({
                label: x.name,
                value: x.name
              }))}
              onChange={x => auth.selectAccountByName(x)}
            />
            <TextField
              id="id-public-key-base16"
              label="Public Key (Base16)"
              value={
                auth.selectedAccount &&
                base64to16(auth.selectedAccount.publicKeyBase64)
              }
              readonly={true}
            />
          </Form>
          <Button
            title="Request tokens"
            disabled={auth.selectedAccount == null}
            onClick={() => props.requestTokens(auth.selectedAccount!)}
          />
        </div>
        <div className="card-footer small text-muted">
          Select an account and request tokens for it from the Faucet.
          Currently, a given account can only request tokens once. It can take
          some time for your request to be processed; the status of your request
          will be updated when tokens are available and you can use your
          account.
        </div>
      </div>
    );
  }
);

const CliHint = observer((props: { requests: FaucetRequest[] }) =>
  props.requests.length > 0 ? (
    <CommandLineHint>
      <p>
        You can also monitor the outcome of the deploys using the{' '}
        <a
          href="https://github.com/CasperLabs/CasperLabs/blob/dev/README.md#cli-client-tool-1"
          target="_blank"
          rel="noopener noreferrer"
        >
          casperlabs-client
        </a>
        :
      </p>
      <pre>
        {
          'casperlabs-client --host deploy.casperlabs.io --port 40401 show-deploy <deploy-hash>'
        }
      </pre>
    </CommandLineHint>
  ) : null
);

const StatusTable = observer(
  (props: {
    requests: FaucetRequest[];
    onRefresh: () => void;
    lastRefresh?: Date;
  }) => (
    <DataTable
      title="Recent Faucet Requests"
      refresh={() => props.onRefresh()}
      rows={props.requests}
      headers={['Timestamp', 'Account', 'Deploy Hash', 'Status']}
      renderRow={(request: FaucetRequest, idx: number) => {
        return (
          <tr key={idx}>
            <td>{request.timestamp.toLocaleString()}</td>
            <td>{request.account.name}</td>
            <td>{encodeBase16(request.deployHash)}</td>
            <StatusCell request={request} />
          </tr>
        );
      }}
      footerMessage={<span>Wait until the deploy is included in a block.</span>}
    />
  )
);

const StatusCell = observer((props: { request: FaucetRequest }) => {
  const info = props.request.deployInfo;
  const iconAndMessage: () => [any, string | undefined] = () => {
    if (info) {
      const attempts = info.processingResultsList.slice().reverse();
      const success = attempts.find(x => !x.isError);
      const failure = attempts.find(x => x.isError);
      const blockHash = (result: DeployInfo.ProcessingResult.AsObject) =>
        encodeBase16(result.blockInfo!.summary!.blockHash as ByteArray);
      if (success)
        return [
          <Icon name="check-circle" color="green" />,
          `Successfully included in block ${blockHash(success)}`
        ];
      if (failure) {
        const errm = failure.errorMessage;
        const hint =
          errm === 'Exit code: 1'
            ? '. It looks like you already funded this account!'
            : errm === 'Exit code: 2'
            ? '. It looks like the faucet ran out of funds!'
            : '';
        return [
          <Icon name="times-circle" color="red" />,
          `Failed in block ${blockHash(failure)}: ${errm + hint}`
        ];
      }
    }
    return [<Icon name="clock" />, 'Pending...'];
  };
  const [icon, message] = iconAndMessage();
  return (
    <td className="text-center" title={message}>
      {icon}
    </td>
  );
});

export default Faucet;

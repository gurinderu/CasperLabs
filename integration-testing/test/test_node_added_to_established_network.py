from test.cl_node.casperlabsnode import HELLO_NAME
from test.cl_node.wait import (
    get_new_blocks_requests_total,
    wait_for_blocks_count_at_least,
    wait_for_gossip_metrics_and_assert_blocks_gossiped,
)


def test_newly_joined_node_should_not_gossip_blocks(two_node_network):
    """
    Feature file: node_added_to_established_network.feature
    Scenario: New node should not gossip old blocks back to network
    """
    network = two_node_network

    def wait_for_blocks_propagated(n):
        for node in network.docker_nodes:
            wait_for_blocks_count_at_least(node, n, n, node.timeout)

    def propose(node, n):
        block_hash = node.deploy_and_propose(session_contract=HELLO_NAME, payment_contract=HELLO_NAME)
        wait_for_blocks_propagated(n)
        return block_hash

    block_hashes = [propose(node, 1+1+i) for i, node in enumerate(network.docker_nodes)]

    # Wait until both nodes have the genesis plus the two blocks they proposed and gossiped.
    wait_for_blocks_propagated(3)

    # Add a new node, it should sync with the old ones.
    network.add_new_node_to_network()
    wait_for_blocks_propagated(3)

    node0, node1, node2 = network.docker_nodes
    for i, block_hash in enumerate(block_hashes):
        assert f"Attempting to add Block #{i+1} ({block_hash}...)" in node2.logs()

    # Verify that the new node didn't do any gossiping.
    wait_for_gossip_metrics_and_assert_blocks_gossiped(node2, node2.timeout, 0)

    # Verify that the original nodes didn't get their NewBlocks method called more times then expected.
    for node in network.docker_nodes[:2]:
        # node0 tells node1 about its block then node1 will try to reflect that back; 2 blocks
        # node2 should not have called the old ones during sync.
        assert get_new_blocks_requests_total(node) <= 2

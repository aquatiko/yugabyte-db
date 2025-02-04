// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.controllers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.inject.Inject;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.KubernetesManager;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.common.PlacementInfoUtil;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yugabyte.yw.commissioner.tasks.UniverseDefinitionTaskBase.ServerType;
import com.yugabyte.yw.common.ApiResponse;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;

import play.mvc.Controller;
import play.mvc.Result;

import static com.yugabyte.yw.forms.UniverseDefinitionTaskParams.ExposingServiceState;

public class MetaMasterController extends Controller {

  public static final Logger LOG = LoggerFactory.getLogger(MetaMasterController.class);

  @Inject KubernetesManager kubernetesManager;

  public Result get(UUID universeUUID) {
    // Lookup the entry for the instanceUUID.
    Universe universe = Universe.getOrBadRequest(universeUUID);
    // Return the result.
    MastersList masters = new MastersList();
    for (NodeDetails node : universe.getMasters()) {
      masters.add(MasterNode.fromUniverseNode(node));
    }
    return ApiResponse.success(masters);
  }

  public Result getMasterAddresses(UUID customerUUID, UUID universeUUID) {
    return getServerAddresses(customerUUID, universeUUID, ServerType.MASTER);
  }

  public Result getYQLServerAddresses(UUID customerUUID, UUID universeUUID) {
    return getServerAddresses(customerUUID, universeUUID, ServerType.YQLSERVER);
  }

  public Result getYSQLServerAddresses(UUID customerUUID, UUID universeUUID) {
    return getServerAddresses(customerUUID, universeUUID, ServerType.YSQLSERVER);
  }

  public Result getRedisServerAddresses(UUID customerUUID, UUID universeUUID) {
    return getServerAddresses(customerUUID, universeUUID, ServerType.REDISSERVER);
  }

  private Result getServerAddresses(UUID customerUUID, UUID universeUUID, ServerType type) {
    // Verify the customer with this universe is present.
    Customer customer = Customer.get(customerUUID);
    if (customer == null) {
      return ApiResponse.error(BAD_REQUEST, "Invalid Customer UUID: " + customerUUID);
    }

    // Lookup the entry for the instanceUUID.
    Universe universe = Universe.getOrBadRequest(universeUUID);
    // In case of Kubernetes universe we would fetch the service ip
    // instead of the POD ip.
    String serviceIPPort = getKuberenetesServiceIPPort(type, universe);
    if (serviceIPPort != null) {
      return ApiResponse.success(serviceIPPort);
    }

    switch (type) {
      case MASTER:
        return ApiResponse.success(universe.getMasterAddresses());
      case YQLSERVER:
        return ApiResponse.success(universe.getYQLServerAddresses());
      case YSQLSERVER:
        return ApiResponse.success(universe.getYSQLServerAddresses());
      case REDISSERVER:
        return ApiResponse.success(universe.getRedisServerAddresses());
      default:
        throw new IllegalArgumentException("Unexpected type " + type);
    }
  }

  public static class MastersList {
    public List<MasterNode> masters = new ArrayList<>();

    public void add(MasterNode masterNode) {
      masters.add(masterNode);
    }
  }

  public static class MasterNode {
    // Information about the node that is returned by the cloud provider.
    public CloudSpecificInfo cloudInfo;

    // The master rpc port.
    public int masterRpcPort;

    public static MasterNode fromUniverseNode(NodeDetails uNode) {
      MasterNode mNode = new MasterNode();
      mNode.cloudInfo = uNode.cloudInfo;
      mNode.masterRpcPort = uNode.masterRpcPort;

      return mNode;
    }
  }

  private String getKuberenetesServiceIPPort(ServerType type, Universe universe) {
    List<String> allIPs = new ArrayList<>();
    UniverseDefinitionTaskParams universeDetails = universe.getUniverseDetails();
    UniverseDefinitionTaskParams.Cluster primary = universeDetails.getPrimaryCluster();
    // If no service is exposed, fail early.
    if (primary.userIntent.enableExposingService == ExposingServiceState.UNEXPOSED) {
      return null;
    }
    Provider provider = Provider.get(UUID.fromString(primary.userIntent.provider));

    if (!primary.userIntent.providerType.equals(Common.CloudType.kubernetes)) {
      return null;
    } else {
      PlacementInfo pi = universeDetails.getPrimaryCluster().placementInfo;

      boolean isMultiAz = PlacementInfoUtil.isMultiAZ(provider);
      Map<UUID, Map<String, String>> azToConfig = PlacementInfoUtil.getConfigPerAZ(pi);

      for (Entry<UUID, Map<String, String>> entry : azToConfig.entrySet()) {
        UUID azUUID = entry.getKey();
        String azName = isMultiAz ? AvailabilityZone.get(azUUID).code : null;

        Map<String, String> config = entry.getValue();

        String namespace =
            PlacementInfoUtil.getKubernetesNamespace(
                isMultiAz, universeDetails.nodePrefix, azName, config);

        ShellResponse r =
            kubernetesManager.getServiceIPs(config, namespace, type == ServerType.MASTER);
        if (r.code != 0 || r.message == null) {
          LOG.warn("Kubernetes getServiceIPs api failed! {}", r.message);
          return null;
        }
        List<String> ips =
            Arrays.stream(r.message.split("\\|"))
                .filter((ip) -> !ip.trim().isEmpty())
                .collect(Collectors.toList());
        int rpcPort;
        switch (type) {
          case MASTER:
            rpcPort = universeDetails.communicationPorts.masterRpcPort;
            break;
          case YSQLSERVER:
            rpcPort = universeDetails.communicationPorts.ysqlServerRpcPort;
            break;
          case YQLSERVER:
            rpcPort = universeDetails.communicationPorts.yqlServerRpcPort;
            break;
          case REDISSERVER:
            rpcPort = universeDetails.communicationPorts.redisServerRpcPort;
            break;
          default:
            throw new IllegalArgumentException("Unexpected type " + type);
        }
        allIPs.add(String.format("%s:%d", ips.get(ips.size() - 1), rpcPort));
      }
      return String.join(",", allIPs);
    }
  }
}

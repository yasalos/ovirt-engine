package org.ovirt.engine.core.bll.network.cluster;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.ovirt.engine.core.bll.MultiLevelAdministrationHandler;
import org.ovirt.engine.core.bll.PredefinedRoles;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.interfaces.BackendInternal;
import org.ovirt.engine.core.bll.network.HostSetupNetworksParametersBuilder;
import org.ovirt.engine.core.bll.network.RemoveNetworkParametersBuilder;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionParametersBase;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.businessentities.network.Network;
import org.ovirt.engine.core.common.businessentities.network.NetworkFilter;
import org.ovirt.engine.core.common.businessentities.network.VdsNetworkInterface;
import org.ovirt.engine.core.common.businessentities.network.VnicProfile;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.network.InterfaceDao;
import org.ovirt.engine.core.dao.network.NetworkDao;
import org.ovirt.engine.core.dao.network.NetworkFilterDao;
import org.ovirt.engine.core.dao.network.VnicProfileDao;
import org.ovirt.engine.core.utils.NetworkUtils;

/**
 * Class to hold common network methods that are used in several different places.
 */
@Singleton
public class NetworkHelper {

    @Inject
    private BackendInternal backend;

    @Inject
    private VnicProfileDao vnicProfileDao;

    @Inject
    private NetworkDao networkDao;

    @Inject
    private InterfaceDao interfaceDao;

    @Inject
    private NetworkFilterDao networkFilterDao;

    @Inject
    private RemoveNetworkParametersBuilder removeNetworkParametersBuilder;

    /**
     * Grants permissions on the network entity to the given user
     *
     * @param userId
     *            the ID of the user to get the permission
     * @param networkId
     *            the Network ID
     */
    public void addPermissionsOnNetwork(Guid userId, Guid networkId) {
        MultiLevelAdministrationHandler.addPermission(userId, networkId, PredefinedRoles.NETWORK_ADMIN, VdcObjectType.Network);
    }

    /**
     * Grants permissions on the vnic profile entity to its creator and usage permission to 'everyone' if publicUse is
     * set to <code>true</code>
     *
     * @param userId
     *            the ID of the user to get the permission
     * @param vnicProfileId
     *            the VNIC Profile
     * @param publicUse
     *            Indicates of the network is intended for a public user
     */
    public void addPermissionsOnVnicProfile(Guid userId, Guid vnicProfileId, boolean publicUse) {
        MultiLevelAdministrationHandler.addPermission(userId,
                vnicProfileId,
                PredefinedRoles.NETWORK_ADMIN,
                VdcObjectType.VnicProfile);

        // if the profile is for public use, set EVERYONE as a VNICProfileUser on the profile
        if (publicUse) {
            MultiLevelAdministrationHandler.addPermission(MultiLevelAdministrationHandler.EVERYONE_OBJECT_ID,
                    vnicProfileId,
                    PredefinedRoles.VNIC_PROFILE_USER,
                    VdcObjectType.VnicProfile);
        }
    }

    public VnicProfile createVnicProfile(Network net) {
        VnicProfile profile = new VnicProfile();
        profile.setId(Guid.newGuid());
        profile.setName(net.getName());
        profile.setNetworkId(net.getId());
        profile.setPortMirroring(false);

        NetworkFilter defaultNetworkFilter = resolveVnicProfileDefaultNetworkFilter();
        profile.setNetworkFilterId(defaultNetworkFilter == null ? null : defaultNetworkFilter.getId());
        return profile;
    }

    public NetworkFilter resolveVnicProfileDefaultNetworkFilter() {
        if (Config.<Boolean> getValue(ConfigValues.EnableMACAntiSpoofingFilterRules)) {
            return networkFilterDao.getNetworkFilterByName(NetworkFilter.VDSM_NO_MAC_SPOOFING);
        }
        return null;
    }

    public Network getNetworkByVnicProfileId(Guid vnicProfileId) {
        VnicProfile vnicProfile = getVnicProfile(vnicProfileId);
        return getNetworkByVnicProfile(vnicProfile);
    }

    public VnicProfile getVnicProfile(Guid vnicProfileId) {
        if (vnicProfileId == null) {
            return null;
        }

        return vnicProfileDao.get(vnicProfileId);
    }

    public Network getNetworkByVnicProfile(VnicProfile vnicProfile) {
        if (vnicProfile == null || vnicProfile.getNetworkId() == null) {
            return null;
        }

        return networkDao.get(vnicProfile.getNetworkId());
    }

    public boolean isNetworkInCluster(Network network, Guid clusterId) {
        if (clusterId == null) {
            return false;
        }

        List<Network> networks = networkDao.getAllForCluster(clusterId);
        for (Network clusterNetwork : networks) {
            if (clusterNetwork.getId().equals(network.getId())) {
                return true;
            }
        }

        return false;
    }

    public void removeNetworkFromHostsInDataCenter(Network network, Guid dataCenterId, CommandContext context) {
        List<VdsNetworkInterface> nics = interfaceDao.getAllInterfacesByLabelForDataCenter(dataCenterId, network.getLabel());
        removeNetworkFromHosts(network, context, nics);
    }

    private void removeNetworkFromHosts(Network network, CommandContext context, List<VdsNetworkInterface> nics) {
        ArrayList<ActionParametersBase> parameters = removeNetworkParametersBuilder.buildParameters(network, nics);

        if (!parameters.isEmpty()) {
            HostSetupNetworksParametersBuilder.updateParametersSequencing(parameters);
            backend.runInternalMultipleActions(ActionType.PersistentHostSetupNetworks, parameters, context);
        }
    }

    public static boolean shouldRemoveNetworkFromHostUponNetworkRemoval(Network persistedNetwork) {
        return !persistedNetwork.isExternal() && NetworkUtils.isLabeled(persistedNetwork);
    }
}

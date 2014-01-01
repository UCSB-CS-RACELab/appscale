/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package edu.ucsb.cs.eager.service;

import edu.ucsb.cs.eager.internal.EagerAPIManagementComponent;
import edu.ucsb.cs.eager.models.APIInfo;
import edu.ucsb.cs.eager.models.DependencyInfo;
import edu.ucsb.cs.eager.models.EagerException;
import edu.ucsb.cs.eager.models.ValidationInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.model.*;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerFactory;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class EagerAdmin {

    private static final Log log = LogFactory.getLog(EagerAdmin.class);

    private static final String EAGER_DOC_NAME = "EagerSpec";

    public boolean isAPIAvailable(APIInfo api) throws EagerException {
        try {
            String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
            APIProvider provider = getAPIProvider(eagerAdmin);
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, api.getName(), api.getVersion());
            return provider.isAPIAvailable(apiId);
        } catch (APIManagementException e) {
            handleException("Error while checking for the existence of API", e);
            return false;
        }
    }

    public APIInfo[] getAPIsWithContext(String context) throws EagerException {
        String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
        try {
            APIProvider provider = getAPIProvider(eagerAdmin);
            List<API> apiList = provider.getAllAPIs();
            List<APIInfo> results = new ArrayList<APIInfo>();
            for (API api : apiList) {
                if (api.getContext().equals(context)) {
                    results.add(new APIInfo(api.getId()));
                }
            }
            return results.toArray(new APIInfo[results.size()]);
        } catch (APIManagementException e) {
            handleException("Error while retrieving APIs", e);
            return null;
        }
    }

    public boolean validateDependencies(APIInfo api,
                                           DependencyInfo[] dependencies) throws EagerException {
        String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
        for (DependencyInfo dependency : dependencies) {
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, dependency.getName(),
                    dependency.getVersion());
            APIInfo dependencyApi = new APIInfo(apiId);
            if (!isAPIAvailable(dependencyApi)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Record the dependencies of an API
     *
     * @param api Dependent API
     * @param dependencies An array of DependencyInfo objects, one per dependency
     * @return a boolean value indicating success or failure
     */
    public boolean recordDependencies(APIInfo api, DependencyInfo[] dependencies) throws EagerException {
        Connection conn = null;
        PreparedStatement ps = null;
        String deleteQuery = "DELETE FROM EAGER_API_DEPENDENCY WHERE " +
                "EAGER_DEPENDENT_NAME=? AND EAGER_DEPENDENT_VERSION=?";
        String insertQuery = "INSERT INTO EAGER_API_DEPENDENCY (EAGER_DEPENDENCY_NAME, " +
                "EAGER_DEPENDENCY_VERSION, EAGER_DEPENDENT_NAME, EAGER_DEPENDENT_VERSION, " +
                "EAGER_DEPENDENCY_OPERATIONS) VALUES (?,?,?,?,?)";
        try {
            conn = APIMgtDBUtil.getConnection();

            ps = conn.prepareStatement(deleteQuery);
            ps.setString(1, api.getName());
            ps.setString(2, api.getVersion());
            ps.executeUpdate();
            ps.close();

            ps = conn.prepareStatement(insertQuery);
            for (DependencyInfo dependency : dependencies) {
                ps.setString(1, dependency.getName());
                ps.setString(2, dependency.getVersion());
                ps.setString(3, api.getName());
                ps.setString(4, api.getVersion());
                ps.setString(5, getOperationsListAsString(dependency));
                ps.addBatch();
            }
            ps.executeBatch();
            ps.clearBatch();
            return true;
        } catch (SQLException e) {
            handleException("Error while recording API dependency", e);
            return false;
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, null);
        }
    }

    private String getOperationsListAsString(DependencyInfo dependency) {
        String ops = "";
        String[] operations = dependency.getOperations();
        if (operations != null) {
            for (int i = 0; i < operations.length; i++) {
                if (i > 0) {
                    ops += ",";
                }
                ops += operations[i];
            }
        }
        return ops;
    }

    private String[] getOperationsListFromString(String operations) {
        if (operations != null) {
            operations = operations.trim();
            if (!"".equals(operations)) {
                return operations.split(",");
            }
        }
        return new String[] { };
    }

    /**
     * Get the information required to perform dependency checking.
     *
     * @param api A potential dependency API
     * @return A ValidationInfo object carrying the specification of the API and its dependents
     */
    public ValidationInfo getValidationInfo(APIInfo api) throws EagerException {
        String selectQuery = "SELECT" +
                " DEP.EAGER_DEPENDENCY_NAME AS DEPENDENCY_NAME," +
                " DEP.EAGER_DEPENDENCY_VERSION AS DEPENDENCY_VERSION," +
                " DEP.EAGER_DEPENDENT_NAME AS DEPENDENT_NAME," +
                " DEP.EAGER_DEPENDENT_VERSION AS DEPENDENT_VERSION," +
                " DEP.EAGER_DEPENDENCY_OPERATIONS AS OPERATIONS " +
                "FROM" +
                " EAGER_API_DEPENDENCY DEP " +
                "WHERE" +
                " DEP.EAGER_DEPENDENCY_NAME=?" +
                " AND DEP.EAGER_DEPENDENCY_VERSION=?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
            APIProvider provider = getAPIProvider(eagerAdmin);
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, api.getName(), api.getVersion());
            String specification = provider.getDocumentationContent(apiId, EAGER_DOC_NAME);

            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(selectQuery);
            ps.setString(1, api.getName());
            ps.setString(2, api.getVersion());
            rs = ps.executeQuery();
            List<DependencyInfo> dependencies = new ArrayList<DependencyInfo>();
            while (rs.next()) {
                DependencyInfo dependency = new DependencyInfo();
                dependency.setName(rs.getString("DEPENDENT_NAME"));
                dependency.setVersion(rs.getString("DEPENDENT_VERSION"));
                dependency.setOperations(getOperationsListFromString(rs.getString("OPERATIONS")));
                dependencies.add(dependency);
            }

            ValidationInfo info = new ValidationInfo();
            info.setSpecification(specification);
            info.setDependents(dependencies.toArray(new DependencyInfo[dependencies.size()]));
            return info;
        } catch (APIManagementException e) {
            handleException("Error while obtaining API validation information", e);
            return null;
        } catch (SQLException e) {
            handleException("Error while obtaining API dependency information", e);
            return null;
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
    }

    public boolean createAPI(APIInfo api, String specification) throws EagerException {
        if (isAPIAvailable(api)) {
            return false;
        }

        try {
            String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
            APIProvider provider = getAPIProvider(eagerAdmin);
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, api.getName(), api.getVersion());
            API newAPI = new API(apiId);
            newAPI.setContext("/" + api.getName().toLowerCase());
            newAPI.setUrl("http://eager4appscale.com");
            newAPI.setStatus(APIStatus.CREATED);

            String[] methods = new String[] {
                "GET", "POST", "PUT", "DELETE", "OPTIONS"
            };
            Set<URITemplate> templates = new HashSet<URITemplate>();
            for (String method : methods) {
                URITemplate template = new URITemplate();
                template.setHTTPVerb(method);
                template.setUriTemplate("/*");
                template.setAuthType(APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN);
                template.setResourceURI("http://eager4appscale.com");
                templates.add(template);
            }
            newAPI.setUriTemplates(templates);
            newAPI.setLastUpdated(new Date());
            newAPI.setVisibility(APIConstants.API_GLOBAL_VISIBILITY);
            newAPI.addAvailableTiers(provider.getTiers());
            provider.addAPI(newAPI);
            log.info("Registered API: " + api.getName() + "-v" + api.getVersion());

            Documentation doc = new Documentation(DocumentationType.OTHER, EAGER_DOC_NAME);
            doc.setSourceType(Documentation.DocumentSourceType.INLINE);
            doc.setLastUpdated(new Date());
            doc.setOtherTypeName(EAGER_DOC_NAME);
            provider.addDocumentation(apiId, doc);
            provider.addDocumentationContent(apiId, EAGER_DOC_NAME, specification);
            return true;
        } catch (APIManagementException e) {
            handleException("Error while creating new API", e);
            return false;
        }
    }

    public boolean updateAPISpec(APIInfo api, String specification) throws EagerException {
        if (!isAPIAvailable(api)) {
            return false;
        }

        try {
            String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
            APIProvider provider = getAPIProvider(eagerAdmin);
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, api.getName(), api.getVersion());

            Documentation doc = new Documentation(DocumentationType.OTHER, EAGER_DOC_NAME);
            doc.setSourceType(Documentation.DocumentSourceType.INLINE);
            doc.setLastUpdated(new Date());
            doc.setOtherTypeName(EAGER_DOC_NAME);
            provider.updateDocumentation(apiId, doc);
            provider.addDocumentationContent(apiId, EAGER_DOC_NAME, specification);
            return true;
        } catch (APIManagementException e) {
            handleException("Error while updating API specification", e);
            return false;
        }
    }

    /**
     * Publish the specified API to the API Store and Gateway.
     *
     * @param api API to be publishes
     * @param url Backend URL to which API should forward traffic
     * @return true if the operation is successful, and false if the API is already in a
     * published state
     * @throws EagerException If the specified API does not exist or if some other
     * runtime error occurs
     */
    public boolean publishAPI(APIInfo api, String url) throws EagerException {
        if (!isAPIAvailable(api)) {
            throw new EagerException("API " + api.getName() + "-v" +
                    api.getVersion() + " does not exist");
        }

        try {
            String eagerAdmin = EagerAPIManagementComponent.getEagerAdmin();
            APIProvider provider = getAPIProvider(eagerAdmin);
            APIIdentifier apiId = new APIIdentifier(eagerAdmin, api.getName(), api.getVersion());
            API existingAPI = provider.getAPI(apiId);
            if (existingAPI.getStatus() != APIStatus.PUBLISHED) {
                existingAPI.setUrl(url);
                String[] methods = new String[] {
                        "GET", "POST", "PUT", "DELETE", "OPTIONS"
                };
                Set<URITemplate> templates = new HashSet<URITemplate>();
                for (String method : methods) {
                    URITemplate template = new URITemplate();
                    template.setHTTPVerb(method);
                    template.setUriTemplate("/*");
                    template.setAuthType(APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN);
                    template.setResourceURI(url);
                    templates.add(template);
                }
                existingAPI.setUriTemplates(templates);
                existingAPI.setLastUpdated(new Date());
                provider.updateAPI(existingAPI);
                provider.changeAPIStatus(existingAPI, APIStatus.PUBLISHED, eagerAdmin, true);
                return true;
            }
        } catch (APIManagementException e) {
            handleException("Error while publishing API", e);
        }
        return false;
    }

    private APIProvider getAPIProvider(String providerName) throws APIManagementException {
        return APIManagerFactory.getInstance().getAPIProvider(providerName);
    }

    private void handleException(String msg, Exception ex) throws EagerException {
        log.error(msg, ex);
        throw new EagerException(msg, ex);
    }

}
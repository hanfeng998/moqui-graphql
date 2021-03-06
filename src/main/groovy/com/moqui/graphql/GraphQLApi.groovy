/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.moqui.graphql

import com.moqui.impl.service.GraphQLSchemaDefinition
import graphql.ExceptionWhileDataFetching
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.GraphQLError
import graphql.execution.batched.BatchedExecutionStrategy
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.resource.ResourceReference
import org.moqui.jcache.MCache
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class GraphQLApi {
    protected final static Logger logger = LoggerFactory.getLogger(GraphQLApi.class)

    @SuppressWarnings("GrFinalVariableAccess")
    protected final ExecutionContextFactory ecf
    @SuppressWarnings("GrFinalVariableAccess")
    final MCache<String, GraphQL> graphQLCache

    final String graphQLCacheName = "service.graphql.graphql"
    final String schemaCacheKey = "schema"

    GraphQLApi(ExecutionContextFactory ecf) {
        this.ecf = ecf
        graphQLCache = ecf.getCache().getLocalCache(graphQLCacheName)
    }

    GraphQLResult execute(String requestString) {
        return execute(requestString, null, (Object) null, Collections.<String, Object> emptyMap())
    }

    GraphQLResult execute(String requestString, Map<String, Object> arguments) {
        return execute(requestString, null, (Object) null, arguments)
    }

    GraphQLResult execute(String requestString, String operationName, Object context, Map<String, Object> arguments) {
        GraphQL graphQL = graphQLCache.get(schemaCacheKey)

        if (graphQL == null) {
            synchronized (this) {
                if (graphQL == null) {
                    GraphQLSchemaDefinition.clearAllCachedGraphQLTypes()
                    loadSchemaNode()
                    graphQL = graphQLCache.get(schemaCacheKey)
                }
            }
        }

        ExecutionResult executionResult = graphQL.execute("${requestString}", operationName, context, arguments)
        return new GraphQLResult(executionResult)
    }

    synchronized void loadSchemaNode() {
        long startTime = System.currentTimeMillis()
        List<MNode> schemaNodeList = new ArrayList<>()

        // find *.graphql.xml files in component/service directories
        for (String location in this.ecf.getComponentBaseLocations().values()) {
            ResourceReference serviceDirRr = this.ecf.getResource().getLocationReference(location + "/service")
            if (serviceDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!serviceDirRr.exists || !serviceDirRr.isDirectory()) continue
                for (ResourceReference rr in serviceDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".graphql.xml")) continue

                    logger.info("Loading ${rr.fileName}")
                    // Parse .graphql.xml, add schema to cache
                    MNode schemaNode = MNode.parse(rr)
                    schemaNodeList.add(schemaNode)
                }
            } else {
                logger.warn("Can't load GraphQL APIs from component at [${serviceDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
        GraphQLSchemaDefinition schemaDef = new GraphQLSchemaDefinition(ecf, schemaNodeList)
        graphQLCache.put(schemaCacheKey, new GraphQL(schemaDef.getSchema(), new BatchedExecutionStrategy()))

        logger.info("Loaded GraphQL schema, in ${System.currentTimeMillis() - startTime}ms")
    }

    static class GraphQLResult {
        Map responseObj = new HashMap<String, Object>()

        GraphQLResult(ExecutionResult executionResult) {
            if (executionResult.getErrors().size() > 0) {
                List<Map<String, Object>> errors = new ArrayList<>()
                Map<String, Object> errorMap

                for (GraphQLError error in executionResult.getErrors()) {
                    errorMap = new LinkedHashMap<>()
                    errorMap.put('message', error.getMessage())
                    errorMap.put('errorType', error.getErrorType())
                    if (error instanceof ExceptionWhileDataFetching) {
                        Throwable t = ((ExceptionWhileDataFetching) error).getException()
                        if (t instanceof DataFetchingException)
                            errorMap.put('errorCode', ((DataFetchingException) t).errorCode)
                    }
                    errors.add(errorMap)
                }
                responseObj.put('errors', errors)
            }
            responseObj.put("data", executionResult.getData())
        }

    }

}

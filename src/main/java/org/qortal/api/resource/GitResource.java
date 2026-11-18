package org.qortal.api.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.qortal.api.ApiError;
import org.qortal.api.ApiErrors;
import org.qortal.api.ApiExceptionFactory;
import org.qortal.api.Security;
import org.qortal.crypto.Crypto;
import org.qortal.network.reticulum.RNS;
import org.qortal.rngit.RngitBrowse;
import org.qortal.rngit.RngitGit;
import org.qortal.rngit.RngitIdentityBindings;
import org.qortal.rngit.RngitQdn;
import org.qortal.rngit.RngitRepositories;
import org.qortal.rngit.RngitServer;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Read-only access to the git repositories this node's rngit server serves,
 * for Q-Apps and tools: QDN repositories under any registered name, and
 * configured repositories that anyone may read ({@code r:all}). The same
 * {@code <group>/<repo>} paths as over Reticulum.
 * <p>
 * Callers are anonymous, so nothing is served that an {@code all} rule does
 * not grant. Needs {@code rngitEnabled}.
 * <p>
 * {@code /git/identity/{address}} takes precedence over a group literally
 * named "identity", whose repositories are then not reachable here.
 */
@Path("/git")
@Tag(name = "Git")
public class GitResource {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Context
    HttpServletRequest request;

    /** Where the server and registry come from; replaced in tests, which have no running RNS. */
    static Supplier<RngitServer> server = () -> RNS.getInstance().getRngitServer();
    static Supplier<RngitRepositories> registry = GitResource::runningRegistry;

    private static RngitRepositories runningRegistry() {
        RngitServer running = server.get();
        return running == null ? null : running.getRepositories();
    }

    private RngitRepositories repositories() {
        RngitRepositories repositories = registry.get();
        if (repositories == null) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
                    "rngit is not enabled on this node");
        }
        return repositories;
    }

    /** The cached repository path, if it exists and anyone may read it. */
    private java.nio.file.Path readable(String group, String repository) {
        RngitRepositories repositories = repositories();
        RngitRepositories.Repository repo = repositories.getRepository(group, repository);
        if (repo == null || !repositories.isPubliclyReadable(group, repository)) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND,
                    "No such repository: " + group + "/" + repository);
        }
        return repo.getPath();
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.REPOSITORY_ISSUE, e);
        }
    }

    private RuntimeException failure(Exception e) {
        if (e instanceof RngitBrowse.NotFoundException) {
            return ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND, e.getMessage());
        }
        return ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.REPOSITORY_ISSUE, e.getMessage());
    }

    @GET
    @Path("/{group}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List the repositories of a group: a Qortal name's QDN repositories, or a configured group's public ones",
            responses = @ApiResponse(description = "repository names",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = "array"))))
    @ApiErrors({ApiError.INVALID_CRITERIA})
    public String listRepositories(@PathParam("group") String group) {
        return json(repositories().publicRepositories(group));
    }

    @GET
    @Path("/{group}/{repository}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "A repository's refs and HEAD, and for QDN repositories its descriptor details",
            responses = @ApiResponse(description = "repository summary", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getRepository(@PathParam("group") String group, @PathParam("repository") String repository) {
        java.nio.file.Path path = readable(group, repository);
        try {
            String list = RngitGit.listRefs(path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("group", group);
            out.put("repository", repository);
            out.put("qdn", repositories().isQdnGroup(group));
            Map<String, String> refs = new LinkedHashMap<>();
            for (String line : list.split("\n")) {
                String[] parts = line.split(" ", 2);
                if (parts.length != 2) continue;
                if (parts[1].equals("HEAD")) out.put("head", parts[0].substring(1));
                else refs.put(parts[1], parts[0]);
            }
            out.put("refs", refs);
            RngitServer running = server.get();
            if (running != null && running.getDestination() != null) {
                String destination = org.apache.commons.codec.binary.Hex.encodeHexString(running.getDestination().getHash());
                out.put("rnsDestination", destination);
                out.put("cloneUrl", "rns://" + destination + "/" + group + "/" + repository);
            }
            if (repositories().isQdnGroup(group)) {
                RngitQdn.Descriptor descriptor = repositories().qdnDescriptor(group, repository);
                if (descriptor != null) {
                    out.put("description", descriptor.description);
                    out.put("type", descriptor.type);
                    out.put("upstream", descriptor.upstream);
                    out.put("bundles", descriptor.bundles.size());
                }
            }
            return json(out);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/descriptor")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "A QDN repository's current descriptor (rngit.json), e.g. for its owner to edit and republish",
            responses = @ApiResponse(description = "descriptor", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getDescriptor(@PathParam("group") String group, @PathParam("repository") String repository) {
        readable(group, repository);
        RngitQdn.Descriptor descriptor = repositories().qdnDescriptor(group, repository);
        if (descriptor == null) throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND,
                "Only QDN repositories have a descriptor");
        return json(descriptor);
    }

    @GET
    @Path("/{group}/{repository}/log")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Commits reachable from a ref, newest first",
            responses = @ApiResponse(description = "commits", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getLog(@PathParam("group") String group, @PathParam("repository") String repository,
                         @Parameter(description = "branch, tag or SHA (default HEAD)") @QueryParam("ref") String ref,
                         @QueryParam("offset") @DefaultValue("0") int offset,
                         @QueryParam("limit") @DefaultValue("50") int limit) {
        try {
            return json(RngitBrowse.log(readable(group, repository), ref, offset, limit));
        } catch (IOException | RngitBrowse.NotFoundException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/tree")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The entries of a directory at a ref (the root directory without a path)",
            responses = @ApiResponse(description = "directory entries", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getTree(@PathParam("group") String group, @PathParam("repository") String repository,
                          @QueryParam("ref") String ref, @QueryParam("path") String path) {
        try {
            return json(RngitBrowse.tree(readable(group, repository), ref, path));
        } catch (IOException | RngitBrowse.NotFoundException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/blob")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "A file's contents at a ref (up to 10 MiB)",
            responses = @ApiResponse(description = "file bytes", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public byte[] getBlob(@PathParam("group") String group, @PathParam("repository") String repository,
                          @QueryParam("ref") String ref, @QueryParam("path") String path) {
        try {
            return RngitBrowse.blob(readable(group, repository), ref, path);
        } catch (IOException | RngitBrowse.NotFoundException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/commit/{sha}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "A commit, the files it changed and optionally its diff against its first parent",
            responses = @ApiResponse(description = "commit", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getCommit(@PathParam("group") String group, @PathParam("repository") String repository,
                            @PathParam("sha") String sha, @QueryParam("diff") @DefaultValue("false") boolean diff) {
        try {
            return json(RngitBrowse.commit(readable(group, repository), sha, diff));
        } catch (IOException | RngitBrowse.NotFoundException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/staged")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Changes pushed through this node and staged for the name owner to publish",
            description = "On a node that cannot publish for a Qortal name, permitted pushes are staged instead. "
                    + "\"applies\" tells whether a change still applies to the repository's current state.",
            responses = @ApiResponse(description = "staged changes", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getStaged(@PathParam("group") String group, @PathParam("repository") String repository) {
        readable(group, repository);
        try {
            List<Map<String, Object>> staged = repositories().stagedChanges(group, repository);
            if (staged == null) throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
                    "Only QDN repositories have staged changes");
            return json(staged);
        } catch (IOException e) {
            throw failure(e);
        }
    }

    @GET
    @Path("/{group}/{repository}/staged/{id}/publish")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The QDN resources that publish a staged change",
            description = "Prepared against the repository's current descriptor: a bundle with the new objects (if any) "
                    + "and the updated descriptor, each a base64 file. Pass \"resources\" to qortalRequest "
                    + "PUBLISH_MULTIPLE_QDN_RESOURCES as the name owner; the change is cleared once the published "
                    + "descriptor reflects it.",
            responses = @ApiResponse(description = "resources to publish", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.FILE_NOT_FOUND})
    public String getStagedPublish(@PathParam("group") String group, @PathParam("repository") String repository,
                                   @PathParam("id") long id) {
        readable(group, repository);
        try {
            Map<String, Object> prepared = repositories().prepareStagedChange(group, repository, id);
            if (prepared == null) throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.FILE_NOT_FOUND,
                    "No staged change #" + id);
            return json(prepared);
        } catch (org.qortal.api.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiExceptionFactory.INSTANCE.createCustomException(request, ApiError.INVALID_CRITERIA,
                    "Staged change #" + id + " no longer applies: " + e.getMessage());
        }
    }

    @DELETE
    @Path("/{group}/{repository}/staged/{id}")
    @Operation(summary = "Discard a staged change (node operator)",
            responses = @ApiResponse(description = "true if removed", content = @Content(mediaType = MediaType.TEXT_PLAIN)))
    @SecurityRequirement(name = "apiKey")
    @ApiErrors({ApiError.INVALID_CRITERIA, ApiError.UNAUTHORIZED})
    public String deleteStaged(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("group") String group,
                               @PathParam("repository") String repository, @PathParam("id") long id) {
        Security.checkApiCallAllowed(request);
        return Boolean.toString(repositories().removeStagedChange(group, repository, id));
    }

    @GET
    @Path("/identity/{address}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "RNS identities bound to a Qortal account by its published rns-identity record",
            responses = @ApiResponse(description = "address and identity hashes", content = @Content(mediaType = MediaType.APPLICATION_JSON)))
    @ApiErrors({ApiError.INVALID_ADDRESS, ApiError.INVALID_CRITERIA})
    public String getIdentities(@PathParam("address") String address) {
        if (!Crypto.isValidAddress(address)) throw ApiExceptionFactory.INSTANCE.createException(request, ApiError.INVALID_ADDRESS);
        RngitIdentityBindings bindings = repositories().getIdentityBindings();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("address", address);
        out.put("identities", bindings == null ? List.of() : List.copyOf(new TreeSet<>(bindings.boundIdentities(address))));
        return json(out);
    }
}

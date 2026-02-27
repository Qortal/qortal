package org.qortal.api.restricted.resource;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.api.Security;
import org.qortal.arbitrary.ArbitraryDataFile.ResourceIdType;
import org.qortal.arbitrary.ArbitraryDataRenderer;
import org.qortal.arbitrary.ArbitraryDataResource;
import org.qortal.arbitrary.misc.Service;
import org.qortal.controller.arbitrary.ArbitraryDataRenderManager;
import org.qortal.settings.Settings;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;


@Path("/render")
@Tag(name = "Render")
public class RenderResource {

    private static final Logger LOGGER = LogManager.getLogger(RenderResource.class);

    @Context HttpServletRequest request;
    @Context HttpServletResponse response;
    @Context ServletContext context;

    @POST
    @Path("/authorize/{resourceId}")
    @SecurityRequirement(name = "apiKey")
    public boolean authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey, @PathParam("resourceId") String resourceId) {
        Security.checkApiCallAllowed(request);
        Security.disallowLoopbackRequestsIfAuthBypassEnabled(request);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, null, null);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return true;
    }

    @POST
    @Path("authorize/{service}/{resourceId}")
    @SecurityRequirement(name = "apiKey")
    public boolean authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                                     @PathParam("service") Service service,
                                     @PathParam("resourceId") String resourceId) {
        Security.checkApiCallAllowed(request);
        Security.disallowLoopbackRequestsIfAuthBypassEnabled(request);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, service, null);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return true;
    }

    @POST
    @Path("authorize/{service}/{resourceId}/{identifier}")
    @SecurityRequirement(name = "apiKey")
    public boolean authorizeResource(@HeaderParam(Security.API_KEY_HEADER) String apiKey,
                                     @PathParam("service") Service service,
                                     @PathParam("resourceId") String resourceId,
                                     @PathParam("identifier") String identifier) {
        Security.checkApiCallAllowed(request);
        Security.disallowLoopbackRequestsIfAuthBypassEnabled(request);
        ArbitraryDataResource resource = new ArbitraryDataResource(resourceId, null, service, identifier);
        ArbitraryDataRenderManager.getInstance().addToAuthorizedResources(resource);
        return true;
    }

    @GET
    @Path("/signature/{signature}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexBySignature(@PathParam("signature") String signature,
                                                   @QueryParam("theme") String theme, @QueryParam("lang") String lang,
                                                   @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, signature, Service.WEBSITE, null);

        return this.get(signature, ResourceIdType.SIGNATURE, null, null, "/", null, "/render/signature", true, true, theme, lang, themePalette);
    }

    @GET
    @Path("/signature/{signature}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathBySignature(@PathParam("signature") String signature, @PathParam("path") String inPath,
                                                  @QueryParam("theme") String theme, @QueryParam("lang") String lang,
                                                  @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, signature, Service.WEBSITE, null);

        return this.get(signature, ResourceIdType.SIGNATURE, null, null, inPath,null, "/render/signature", true, true, theme, lang, themePalette);
    }

    @GET
    @Path("/hash/{hash}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByHash(@PathParam("hash") String hash58, @QueryParam("secret") String secret58,
                                              @QueryParam("theme") String theme,  @QueryParam("lang") String lang,
                                              @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, hash58, Service.WEBSITE, null);

        return this.get(hash58, ResourceIdType.FILE_HASH, Service.ARBITRARY_DATA, null, "/", secret58, "/render/hash", true, false, theme, lang, themePalette);
    }

    @GET
    @Path("/hash/{hash}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByHash(@PathParam("hash") String hash58, @PathParam("path") String inPath,
                                             @QueryParam("secret") String secret58,
                                             @QueryParam("theme") String theme, @QueryParam("lang") String lang,
                                             @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, hash58, Service.WEBSITE, null);

        return this.get(hash58, ResourceIdType.FILE_HASH, Service.ARBITRARY_DATA, null, inPath, secret58, "/render/hash", true, false, theme, lang, themePalette);
    }

    @GET
    @Path("{service}/{name}/{path:.*}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getPathByName(@PathParam("service") Service service,
                                             @PathParam("name") String name,
                                             @PathParam("path") String inPath,
                                             @QueryParam("identifier") String identifier,
                                             @QueryParam("theme") String theme, @QueryParam("lang") String lang,
                                             @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, name, service, null);

        String prefix = String.format("/render/%s", service);
        return this.get(name, ResourceIdType.NAME, service, identifier, inPath, null, prefix, true, true, theme, lang, themePalette);
    }

    @GET
    @Path("{service}/{name}")
    @SecurityRequirement(name = "apiKey")
    public HttpServletResponse getIndexByName(@PathParam("service") Service service,
                                              @PathParam("name") String name,
                                              @QueryParam("identifier") String identifier,
                                              @QueryParam("theme") String theme, @QueryParam("lang") String lang,
                                              @QueryParam("themePalette") String themePalette) {
        if (!Settings.getInstance().isQDNAuthBypassEnabled())
            Security.requirePriorAuthorization(request, name, service, null);

        String prefix = String.format("/render/%s", service);
        return this.get(name, ResourceIdType.NAME, service, identifier, "/", null, prefix, true, true, theme, lang, themePalette);
    }



    private HttpServletResponse get(String resourceId, ResourceIdType resourceIdType, Service service, String identifier,
                                    String inPath, String secret58, String prefix, boolean includeResourceIdInPrefix, boolean async,
                                    String theme, String lang, String themePalette) {

        ArbitraryDataRenderer renderer = new ArbitraryDataRenderer(resourceId, resourceIdType, service, identifier, inPath,
                secret58, prefix, includeResourceIdInPrefix, async, "render", request, response, context);

        if (theme != null) {
            renderer.setTheme(theme);
        }
        if (lang != null) {
            renderer.setLang(lang);
        }
        if (themePalette != null) {
            renderer.setThemePalette(themePalette);
        }
        return renderer.render();
    }

}

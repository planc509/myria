package edu.washington.escience.myria.api;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import edu.washington.escience.myria.MyriaSystemConfigKeys;
import edu.washington.escience.myria.api.encoding.VersionEncoding;
import edu.washington.escience.myria.daemon.MasterDaemon;
import edu.washington.escience.myria.parallel.Server;

/**
 * This is the class that handles API calls that return workers.
 * 
 * @author jwang
 */
@Path("/server")
public final class MasterResource {
  /** How long the thread should sleep before shutting down the daemon. This is a HACK! TODO */
  private static final int SLEEP_BEFORE_SHUTDOWN_MS = 100;

  /**
   * Shutdown the server.
   * 
   * @param daemon the Myria {@link MasterDaemon} to be shutdown.
   * @param sc security context.
   * @return an HTTP 204 (NO CONTENT) response.
   */
  @GET
  @RolesAllowed({ "admin" })
  @Path("/shutdown")
  public Response shutdown(@Context final MasterDaemon daemon) {
    /* A thread to stop the daemon after this request finishes. */
    Thread shutdownThread = new Thread("MasterResource-Shutdown") {
      @Override
      public void run() {
        try {
          Thread.sleep(SLEEP_BEFORE_SHUTDOWN_MS);
          daemon.stop();
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    };

    /* Start the thread, then return an empty success response. */
    shutdownThread.start();
    return Response.noContent().build();
  }

  /**
   * Get the version information of Myria at build time.
   * 
   * @return a {@link VersionEncoding}.
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Response getVersion() {
    return Response.ok(new VersionEncoding()).build();
  }

  @GET
  @RolesAllowed({ "admin" })
  @Path("/deployment_cfg")
  public Response getDeploymentCfg(@Context final Server server) {
    String workingDir = server.getConfiguration(MyriaSystemConfigKeys.WORKING_DIRECTORY);
    String description = server.getConfiguration(MyriaSystemConfigKeys.DESCRIPTION);
    String fileName = server.getConfiguration(MyriaSystemConfigKeys.DEPLOYMENT_FILE);
    String deploymentFile = workingDir + "/" + description + "-files" + "/" + description + "/" + fileName;
    return Response.ok(deploymentFile).build();
  }
}

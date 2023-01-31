import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.commons.lang3.StringUtils;
import org.apache.coyote.ActionCode;
import org.apache.tomcat.util.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.websocket.servlet.TomcatWebSocketServletWebServerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.stereotype.Component;

/**
 * The WebServerCustomizer class to extends {@link #TomcatWebSocketServletWebServerCustomizer} and
 * Customize the Tomcat server default error page like page not found etc.
 */
@Component
public class WebServerCustomizer extends TomcatWebSocketServletWebServerCustomizer
{

   @Value("#{systemEnvironment['SERVER_SERVLET_CONTEXT_PATH'] ?: ''}")
   public String contextPath;

   /**
    * 
    * @see org.springframework.boot.autoconfigure.websocket.servlet.TomcatWebSocketServletWebServerCustomizer#customize(org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory)
    */
   @Override
   public void customize(TomcatServletWebServerFactory factory)
   {
      factory.addContextCustomizers((context) -> {

         if (context.getParent() instanceof StandardHost)
         {
            StandardHost parent = (StandardHost) context.getParent();
            parent.setErrorReportValveClass(MyWorksErrorReportValve.class.getName());
            parent.addValve(new MyWorksErrorReportValve());
         }
      });
   }

   /**
    *
    * @see org.springframework.boot.autoconfigure.websocket.servlet.TomcatWebSocketServletWebServerCustomizer#getOrder()
    */
   @Override
   public int getOrder()
   {
      // needs to be AFTER the one configured with TomcatWebServerFactoryCustomizer
      return 100;
   }

   /**
    * The MyWorksErrorReportValve class is implementation of a Valve that outputs HTML error
    * pages. This Valve should be attached at the Host level, although it will work if attached to a
    * Context.
    */
   private class MyErrorReportValve extends ErrorReportValve
   {

      @Override
      protected void report(Request request, Response response, Throwable throwable)
      {
         int statusCode = response.getCoyoteResponse().getStatus();

         /*
          * Do nothing on a 1xx, 2xx and 3xx status.
          * Do nothing if anything has been written already.
          * Do nothing if the response hasn't been explicitly marked as in error, and that error has not been reported.
          */
         if (statusCode < 400 || response.getContentWritten() > 0 || !response.setErrorReported())
         {
            return;
         }

         /*
          * If an error has occurred that prevents further I/O, don't waste time
          * producing an error report that will never be read
          */
         AtomicBoolean result = new AtomicBoolean(false);
         response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, result);
         if (!result.get())
         {
            return;
         }

         try
         {
            String uri = request.getRequestURI();

            /*
             * Request is for APIs resources then will generate the JSON response and return
             * instead of a html error page.
             */
            if (uri != null && StringUtils.contains(uri, "/api"))
            {

               String error = "Error message";
               String errorMessage = "Error message body";

               String jsonBody = "{\r\n" + "    \"status\": " + statusCode + ",\r\n" + "    \"error\": \"" + error
                  + "\",\r\n" + "    \"message\": \"" + errorMessage + "\"\r\n" + "}";
               try
               {
                  response.setContentType("application/json");
                  response.setCharacterEncoding("utf-8");
               }
               catch (Throwable t)
               {
                  ExceptionUtils.handleThrowable(t);
                  if (container.getLogger().isDebugEnabled())
                  {
                     container.getLogger().debug("Failure to set the content-type of response", t);
                  }
               }
               Writer writer = response.getReporter();
               if (writer != null)
               {
                  writer.write(jsonBody);
                  response.finishResponse();
                  return;
               }
            }
            // Return a html error page if the request is not for APIs.
            else
            {
               String url = "/error";

               request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, statusCode);
               RequestDispatcher requestDispatcher = request.getRequestDispatcher(url);

               /*
                *  There are few request does not have RequestDispatcher
                *  If requestDispatcher is null then redirect to error page url with error code as parameter.
                */
               if (requestDispatcher == null)
               {
                  /*
                   * There are chances to 
                   * Read context path to forward request to error page resource under
                   * templates/error.html
                   */
                  url = StringUtils.isNotBlank(contextPath)
                     ? StringUtils.endsWith(contextPath, "/") ? contextPath + "error" : contextPath + "/error" : url;
                  url = String.format("%s?statusCode=%d", url, statusCode);
                  response.sendRedirect(url);
               }
               else
               {
                  requestDispatcher.forward(request, response);
               }

            }
         }
         catch (IOException | ServletException e)
         {
            response.setErrorReported();
         }
      }
   }
}

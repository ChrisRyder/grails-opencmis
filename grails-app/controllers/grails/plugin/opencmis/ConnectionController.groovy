package grails.plugin.opencmis

import static groovyx.net.http.ContentType.*
import static groovyx.net.http.Method.*
import groovyx.net.http.*

import org.apache.http.*
import org.apache.http.client.*
import org.apache.http.client.params.*
import org.apache.http.params.*
import org.apache.http.protocol.*
import org.codehaus.groovy.grails.web.mapping.LinkGenerator

class ConnectionController {

    private final static def INMEMORY_REPOSITORY = 'INMEMORY'
    private final static def ALFRESCO_REPOSITORY = 'ALFRESCO'

    LinkGenerator grailsLinkGenerator

    def cmisConnectorService
    def cmisRepositoryHelperService
    def grailsApplication
    def cmisCurrentSession

    def index() {
      
      def alf = 'alfrescoCloud'
      def che = 'chemistryInMemory'
      def rep = cmisRepositoryHelperService.getRepository()
      def action

      if(rep == INMEMORY_REPOSITORY) {
          action = che
      } else if(rep == ALFRESCO_REPOSITORY){
          action = alf
      } else {
        log.error "No repository set" 
      }
      (cmisCurrentSession) ? redirect(action:action) : render(view:'index',model:[rep:rep])
    }

    def alfrescoConnect() {
       if (session["accessToken"]) {
           def accessToken = session["accessToken"]
           def http = new HTTPBuilder("https://api.alfresco.com/")
           http.setHeaders( [ "Authorization" : "Bearer " + accessToken ]) // Header based token passing (preferred)

           http.request(GET, JSON) {
              response.success = { response, json ->
                log.debug "####SUCCESS:\n$response\n\tBody:\n$json"
                [ authenticated : true,
                  success : true,
                  networks : json ]
              }
              response.failure = { response, reader ->
                log.debug "####FAILURE:\n$response\n\tBody:\n$reader.text"
                [ authenticated : true,
                  success : false,
                  errorMessage : "HTTP Status Code ${response.getStatus()} returned by Alfresco" ]
              }
           }
       }
       else {
           [
               authenticated : false,
               alfrescoOAuthAuthorizeURI : "https://api.alfresco.com/auth/oauth/versions/2/authorize",
               clientId : grailsApplication.config.grails?.plugin?.opencmis?.alfresco?.key,
               scope : grailsApplication.config.grails?.plugin?.opencmis?.alfresco?.scope,
               responseType : "code",
               callbackURL : grailsLinkGenerator.link(action : "alfrescoCallback", absolute : true)
           ]
       }
    }

    def alfrescoCallback() {
        
      def authCode = params.code
      if (!authCode) {
            throw new Exception("authCode not provided to callback")
      }
        // Exchange the auth code for an access token (we have a short period of time to do this)
      def apiKey = grailsApplication.config.grails?.plugin?.opencmis?.alfresco?.key
      def secret = grailsApplication.config.grails?.plugin?.opencmis?.alfresco?.secret
      def http = new HTTPBuilder("https://api.alfresco.com/")

		  String alfrescoCallback = grailsLinkGenerator.link(action : "alfrescoCallback", absolute : true)
        log.debug "About to POST https://api.alfresco.com/auth/oauth/versions/2/token\n" +
                  "\tcode : $authCode\n" +
                  "\tclient_id : $apiKey\n" +
                  "\tclient_secret : $secret\n" +
                  "\tredirect_uri : $alfrescoCallback\n" +
                  "\tgrant_type : authorization_code"

      http.request(POST, JSON) {
          uri.path = "/auth/oauth/versions/2/token"
          requestContentType = ContentType.URLENC
          body = [ code : authCode,
                   client_id : apiKey,
                   client_secret : secret,
                   redirect_uri : alfrescoCallback,
                   grant_type : "authorization_code" ]
        response.success = { response, json ->
            session["accessToken"] = json["access_token"] // Note: shoving the token in the session is fine for a demo app, but _NOT_ ok in a real app!
            session["refreshToken"] = json["refresh_token"]
            log.debug "####SUCCESS:\n$response\n$json"
        }
        response.failure = { response -> log.debug "####FAILURE:\n$response" }
      }
    }

    def alfrescoCloud() {
      def networkId = params.id
      def accessToken = session["accessToken"]

      if (accessToken) {
         cmisConnectorService.connect(accessToken)
         def cmisSession = cmisConnectorService.getCurrentSession()
         def name = cmisSession.getRepositoryInfo().getName()
         def rf = cmisSession.getRootFolder().getName()
        if(cmisSession) { 
            this.cmisCurrentSession = cmisSession
            session['cmis'] = cmisSession
            if(name && rf) {
              [ authenticated : true, success : true, repoName : name , rootFolder: rf]
            } else {
              resetSession()
            }
        } 
      } else {
          log.debug "connection error: no token available"
          return 
      }
    }

  def chemistryInMemory() {
    cmisConnectorService.connect('noToken')
    def cmisSession = cmisConnectorService.getCurrentSession()
    def name = cmisSession.getRepositoryInfo().getName()
    def rf = cmisSession.getRootFolder().getName()
    if(cmisSession) {
        this.cmisCurrentSession = cmisSession
        session['cmis'] = cmisSession
        if(name && rf) {
          [ authenticated : true, success : true, repoName : name , rootFolder: rf]
        }
      } else {
        resetSession()
      }
  } 

  def resetSession() {
    if(cmisCurrentSession) {
      cmisConnectorService.disconnect(cmisCurrentSession)
    } else {
      log.info "no session to invalidate"
    }
    redirect(action: "index")
  }

}

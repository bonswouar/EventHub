package com.codecademy.eventhub.web.commands;

import java.io.IOException;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.codecademy.eventhub.EventHub;

@Path("/users/migrate")
public class MigrateUser extends Command {
	private final EventHub eventHub;
	
	  @Inject
	  public MigrateUser(EventHub eventHub) {
	    this.eventHub = eventHub;
	  }
	  
	  @Override
	  public synchronized boolean execute(final HttpServletRequest request,
	      final HttpServletResponse response) throws IOException {
	    eventHub.migrateUser(
	        request.getParameter("from_external_user_id"),
	        request.getParameter("to_external_user_id"));
	    response.getWriter().println("\"OK\"");
	    return true;
	  }
}
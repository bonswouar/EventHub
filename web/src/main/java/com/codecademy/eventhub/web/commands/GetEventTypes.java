package com.codecademy.eventhub.web.commands;

import com.codecademy.eventhub.EventHub;
import com.google.gson.Gson;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

@Path("/events/types")
public class GetEventTypes extends Command {
  private final Gson gson;
  private final EventHub eventHub;

  @Inject
  public GetEventTypes(Gson gson, EventHub eventHub) {
    this.gson = gson;
    this.eventHub = eventHub;
  }

  @Override
  public synchronized boolean execute(final HttpServletRequest request,
      final HttpServletResponse response) throws IOException {
      if (this.getIsAuthorized()) {
		  List<String> eventTypes = eventHub.getEventTypes();
	    response.getWriter().println(gson.toJson(eventTypes));
	    return true;
	  } else {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		return false;
	  }
  }
}

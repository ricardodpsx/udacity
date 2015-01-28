package com.google.devrel.training.conference.form;

import com.google.api.server.spi.config.AnnotationBoolean;
import com.google.api.server.spi.config.ApiResourceProperty;
import com.google.appengine.repackaged.org.joda.time.DateTime;
import com.google.devrel.training.conference.domain.Conference;
import com.google.devrel.training.conference.domain.Profile;
import com.google.devrel.training.conference.domain.Session;
import com.googlecode.objectify.Key;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Created by ricardo on 26/12/14.
 */
public class SessionForm {
    private String sessionName;
    private List<String> highlights;

    private List<String> speakerProfileKeys;
    int duration;
    Session.SessionType sessionType;
    Date startDate;
    String location;

    //
    String startTime;

    private SessionForm() {

    }

    public SessionForm(String sessionName, List<String> highlights, List<String> speakerProfileKeys,
                       int duration, Session.SessionType sessionType, Date startDate, String location, String startTime) {
        this.sessionName = sessionName;
        this.highlights = highlights;

        this.duration = duration;
        this.sessionType = sessionType;
        this.startDate = startDate;
        this.location = location;
        this.speakerProfileKeys = speakerProfileKeys;
        this.startTime = startTime;
    }

    public String getSessionName() {
        return sessionName;
    }

    public List<String> getHighlights() {
        return highlights;
    }



    public List< String > getSpeakerProfileKeys() {
        return speakerProfileKeys;
    }



    public int getDuration() {
        return duration;
    }

    public String getLocation() { return location; }

    public Session.SessionType getSessionType() {
        return sessionType;
    }

    public Date getStartDate() {
        return startDate;
    }

    public Integer getStartTime() {
        return Session.toTimeInteger(startTime);
    }
}

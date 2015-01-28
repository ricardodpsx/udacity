package com.google.devrel.training.conference.domain;

import com.google.api.server.spi.config.AnnotationBoolean;
import com.google.api.server.spi.config.ApiResourceProperty;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;


/**
 * Created by ricardo on 26/12/14.
 */
@Entity
@Cache
public class Session {


    @Id
    Long id;

    //The Session will belong to it's Conference
    @Parent
    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    private Key<Conference> conferenceKey;


    @Index
    String name;

    //Profile keys of the speakers
    private List<String> speakerProfileKeys;


    @Index
    private Date startDate;


    //Duration of the session in minutes
    @Index
    private int duration;

    @Index
    private int startTime;


    //Highlights
    List<String> highlights;

    public boolean hasSpeaker(String websafeSpeakerKey) {

        return speakerProfileKeys.contains(websafeSpeakerKey);
    }


    //Using an Enum as SessionType because it may not bee very very dynamic
    public enum SessionType {
        WORKSHOP,
        LECTURE,
        KEYNOTE,
        OTHERS
    }

    @Index
    SessionType sessionType;

    //Name of the conference room
    String location;

    public Session(){

    }

    public Session(Key<Conference> conferenceKey, Long id, String name, List<String> speakerProfileKeys, Date startDate,
                   int duration, String location, SessionType sessionType, List<String> highlights, int  startTime) {
        this.id = id;
        this.name = name;
        this.speakerProfileKeys = speakerProfileKeys;
        this.startDate = startDate;
        this.duration = duration;
        this.conferenceKey = conferenceKey;
        this.location = location;
        this.sessionType = sessionType;
        this.highlights = highlights;
        this.startTime = startTime;

    }

    static public Integer toTimeInteger(String time) {

        String[] parts = time.trim().split(":");

        int hours = Integer.valueOf(parts[0]);
        int minutes = Integer.valueOf(parts[1]);

        if(hours < 0 || hours > 23 || minutes < 0 || minutes > 59)
            throw new RuntimeException("Invalid time " + time + " use format: 23:59");

        return  hours * 100 + minutes;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public SessionType getSessionType() {
        return sessionType;
    }


    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    public List<Key<Profile>> getSpeakerProfileKeys() {
        List<Key<Profile>> keys = new ArrayList<>();

        if(speakerProfileKeys != null)
            for(String k : speakerProfileKeys) {
                keys.add( Key.<Profile>create( k ));
            }
        return keys;
    }
    public Date getStartDate() {
        return startDate;
    }

    public int getDuration() {
        return duration;
    }

    @ApiResourceProperty(ignored = AnnotationBoolean.TRUE)
    public Key<Conference> getConferenceKey() {
        return conferenceKey;
    }

    public String getLocation() {
        return location;
    }

    public List<String> getHighlights() { return  highlights; }

    public String getStartTime() {
        return String.valueOf(startTime/100) + ":" + String.valueOf(startTime%100);
    }

    public String getWebsafeKey() {
        return Key.create(conferenceKey, Session.class, id).getString();
    }

}

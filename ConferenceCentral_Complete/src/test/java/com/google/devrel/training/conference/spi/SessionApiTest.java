package com.google.devrel.training.conference.spi;

import com.google.api.server.spi.response.ConflictException;
import com.google.api.server.spi.response.ForbiddenException;
import com.google.api.server.spi.response.NotFoundException;
import com.google.api.server.spi.response.UnauthorizedException;
import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.users.User;
import com.google.appengine.tools.development.testing.LocalDatastoreServiceTestConfig;
import com.google.appengine.tools.development.testing.LocalServiceTestHelper;
import com.google.devrel.training.conference.domain.Conference;
import com.google.devrel.training.conference.domain.Profile;
import com.google.devrel.training.conference.domain.Session;
import com.google.devrel.training.conference.form.ConferenceForm;
import com.google.devrel.training.conference.form.SessionForm;
import com.googlecode.objectify.Key;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.junit.Assert.*;

import static com.google.devrel.training.conference.service.OfyService.ofy;

/**
 * Created by ricardo on 29/12/14.
 */
public class SessionApiTest {

    private ConferenceApi conferenceApi;

    private final LocalServiceTestHelper helper =
            new LocalServiceTestHelper(new LocalDatastoreServiceTestConfig()
                    .setDefaultHighRepJobPolicyUnappliedJobPercentage(100));

    private Profile organizer, drupalSpeaker, invalidUser, medicalSpeaker, assistant;

    Conference drupalConference;
    Conference medicalConference;

    @Before
    public void setUp() throws Exception {
        helper.setUp();

        //Creating some users
        organizer =  new Profile( "0000" , "Medical Speaker", null, null);
        medicalSpeaker = new Profile( "1111"  , "Medical Speaker", null, null);
        drupalSpeaker = new Profile( "2222" , "Drupal Speaker", null, null);
        assistant = new Profile("4444" , "Maria", null, null);


        ofy().save().entities(organizer, drupalSpeaker,  medicalSpeaker, assistant).now();

        invalidUser = new Profile( "3333" , "Invalid User (Non existent in DB)", null, null);


        //Creating test conferences
        drupalConference = new Conference(1,organizer.getUserId(),
                new ConferenceForm("DrupalCon 2015", null, null, null, null, null, 12));


        medicalConference = new Conference(2,organizer.getUserId(),
                new ConferenceForm("Medical Conference 20014", null, null, null, null, null, 12));

        ofy().save().entities(drupalConference, medicalConference).now();


        conferenceApi = new ConferenceApi();
    }

    @After
    public void tearDown() throws Exception {
        ofy().clear();
        helper.tearDown();
    }

    @Test(expected = UnauthorizedException.class)
    public void testCreateSessionWithoutUser() throws Exception {
        conferenceApi.createSession(null, medicalConference.getWebsafeKey(), null);
    }

    @Test(expected = ForbiddenException.class)
    public void testCreateSessionWithNonOrganizerUser() throws Exception {
        conferenceApi.createSession(new User("", "", assistant.getUserId()), medicalConference.getWebsafeKey(), null);
    }


    @Test
    public void testCreateSessionWithOrganizer() throws Exception {

        SessionForm sessionForm = new SessionForm("Drupal Migrations",
                null,
                Arrays.asList(  Key.create(Profile.class, drupalSpeaker.getUserId()).getString()  ),
                20,
                Session.SessionType.KEYNOTE,
                new Date(),
                "Casa Blanca",
                "23:05"
        );

        Session session = conferenceApi.createSession(new User("", "", organizer.getUserId()), medicalConference.getWebsafeKey(),
                sessionForm
        );

        assertEquals(sessionForm.getSessionName(), session.getName());
        assertEquals(sessionForm.getDuration(), session.getDuration());
        assertEquals(sessionForm.getStartDate(), session.getStartDate());

        assertNotNull(session.getConferenceKey().getId());

        assertEquals(session.getConferenceKey().getId() ,medicalConference.getId());

        assertEquals( drupalSpeaker.getDisplayName(), ofy().load().key(session.getSpeakerProfileKeys().get(0)).now().getDisplayName());

    }

    @Test
    public void testGetConferenceSessions() throws Exception {

        //Creating some Sample Sessions into conferences
        Session s1 =  new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ), null, "Drupal Migrations", null,null,0, null, null, null, 2305 );

        ofy().save().entities(
                s1
               ,
                new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ) , null, "Drupal Administration", null,null,0, null, null , null, 2305 )
        ).now();

        Session s2 =  new Session(  Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Cancer", null,null,0, null, null , null, 2305 );
        ofy().save().entities(
                s2,
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Flu", null,null,0, null, null, null, 2305 ),
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Headache", null,null,0, null, null, null, 2305  )
        ).now();

        //Getting sessions with the API
        List<Session> list1 = conferenceApi.getConferenceSessions(drupalConference.getWebsafeKey());
        List<Session> list2 = conferenceApi.getConferenceSessions(medicalConference.getWebsafeKey());

        //Testing
        assertEquals(2, list1.size());
        assertTrue(list1.contains(s1));
        assertFalse(list1.contains(s2));

        assertEquals(3, list2.size());
        assertTrue(list2.contains(s2));
    }

    @Test(expected = NotFoundException.class)
    public void testCreateSessionWithInvalidSpeaker() throws NotFoundException, OAuthRequestException, ForbiddenException, UnauthorizedException, ConflictException {

        Key<Profile> otherKey = Key.create(Profile.class, invalidUser.getUserId());

        //Saving Sessions


        Session drupalSession = conferenceApi.createSession(
                new User("", "", organizer.getUserId()),
                medicalConference.getWebsafeKey(),
                new SessionForm("Drupal Migrations",
                        null,
                        Arrays.asList(otherKey.getString()),
                        20,
                        Session.SessionType.KEYNOTE,
                        new Date(),
                        null, "24:05"
                )
        );

        //Check that the session wasn't created
        assertEquals(ofy().load().type(Session.class).ancestor(Key.create(medicalConference.getWebsafeKey())).list(), 0);

    }

    @Test
    public void testGetConferenceSessionByType() {

        ofy().save().entities(
                new Session(Key.<Conference>create(medicalConference.getWebsafeKey()), null, "Cancer", null, null, 0, null, Session.SessionType.KEYNOTE, null, 2405 ),
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Flu", null,null,0, null, Session.SessionType.LECTURE , null, 2405 ),
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Headache", null,null,0, null, Session.SessionType.LECTURE , null, 2405 )
        ).now();

        assertEquals(1, conferenceApi.getConferenceSessionsByType(medicalConference.getWebsafeKey(), Session.SessionType.KEYNOTE).size());
        assertEquals(2, conferenceApi.getConferenceSessionsByType(medicalConference.getWebsafeKey(), Session.SessionType.LECTURE).size());
        assertEquals(0, conferenceApi.getConferenceSessionsByType(medicalConference.getWebsafeKey(), Session.SessionType.WORKSHOP).size());

        assertEquals( "Cancer", conferenceApi.getConferenceSessionsByType(medicalConference.getWebsafeKey(), Session.SessionType.KEYNOTE).get(0).getName());

    }



    @Test
    public void testGetSessionsBySpeaker() throws  Exception {

        Key<Profile> medicalSpeakerKey = Key.create(Profile.class, medicalSpeaker.getUserId());
        Key<Profile> drupalSpeakerKey = Key.create(Profile.class, drupalSpeaker.getUserId());
        Key<Profile> otherKey = Key.create(Profile.class, assistant.getUserId());

        //Saving Sessions


        Session drupalSession = conferenceApi.createSession(new User("", "", organizer.getUserId()), medicalConference.getWebsafeKey(),
                new SessionForm("Drupal Migrations",
                        null,
                        Arrays.asList( drupalSpeakerKey.getString() ),
                        20,
                        Session.SessionType.KEYNOTE,
                        new Date(),
                        null, "24:05"
                )
        );
        Session medicalSession = conferenceApi.createSession(new User("", "", organizer.getUserId()), medicalConference.getWebsafeKey(),
                new SessionForm("Medical Websites",
                        null,
                        Arrays.asList(medicalSpeakerKey.getString(), drupalSpeakerKey.getString() ),
                        20,
                        Session.SessionType.KEYNOTE,
                        new Date(),
                        null, "24:05"
                )
        );


        List<Profile> profiles = ofy().load().type(Profile.class).list();


        Collection<Session> medicalSpeakerResult = conferenceApi.getSessionsBySpeaker(medicalSpeakerKey.getString());

        assertEquals(1, medicalSpeakerResult.size());
        assertTrue(medicalSpeakerResult.contains(medicalSession));



        Collection<Session> drupalSpeakerResults = conferenceApi.getSessionsBySpeaker(drupalSpeakerKey.getString());
        assertEquals(2, drupalSpeakerResults.size());
        assertTrue(drupalSpeakerResults.contains(drupalSession));
        assertTrue(drupalSpeakerResults.contains(medicalSession));

    }

    @Test
    public void testSessionWishList() throws NotFoundException, UnauthorizedException, ConflictException, OAuthRequestException, ForbiddenException {


        //Creating some Sample Sessions into conferences
        Session s1 =  new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ), null, "Drupal Migrations", null,null,0, null, null, null, 2305 );

        ofy().save().entities(
                s1
                ,
                new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ) , null, "Drupal Administration", null,null,0, null, null , null, 2305 )
        ).now();

        Session s2 =  new Session(  Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Cancer", null,null,0, null, null , null, 2305 );
        Session s3 = new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Flu", null,null,0, null, null, null, 2305 );
        ofy().save().entities(
                s2,
                s3,
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Headache", null,null,0, null, null, null, 2305  )
        ).now();


        conferenceApi.addSessionToWishlist(
                new User("", "", organizer.getUserId()),
                s1.getWebsafeKey()
        );

        conferenceApi.addSessionToWishlist(
                new User("", "", organizer.getUserId()),
                s2.getWebsafeKey()
        );

        Collection < Session > sessions = conferenceApi.getSessionsInWishlist(new User("", "", organizer.getUserId()));

        assertEquals(2, sessions.size());


        assertTrue(sessions.contains(s1));
        assertTrue(sessions.contains(s2));
        assertFalse(sessions.contains(s3));

    }

    @Test
    public void testGetSessionByDates() {

        Date date1 = new Date();
        date1.setYear(2005);
        date1.setDate(12);
        date1.setMonth(11);


        Date date1From = new Date();
        date1.setYear(2005);
        date1.setDate(10);
        date1.setMonth(11);

        Date date1To = new Date();
        date1.setYear(2005);
        date1.setDate(16);
        date1.setMonth(11);

        Date date2 = new Date();
        date1.setYear(2006);
        date1.setDate(12);
        date1.setMonth(11);

        //Creating some Sample Sessions into conferences
        Session s1 =  new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ), null, "Drupal Migrations", null,date1,0, null, null, null, 2305 );

        ofy().save().entities(
                s1
                ,
                new Session( Key.<Conference>create( drupalConference.getWebsafeKey() ) , null, "Drupal Administration", null,date1,0, null, null , null, 2305 )
        ).now();



        Session s2 =  new Session(  Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Cancer", null,date1,0, null, null , null, 2305 );
        Session s3 = new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Flu", null,date2,0, null, null, null, 2305 );
        ofy().save().entities(
                s2,
                s3,
                new Session( Key.<Conference>create( medicalConference.getWebsafeKey() ) , null, "Headache", null,date2,0, null, null, null, 2305  )
        ).now();


        Collection < Session > sessions = conferenceApi.getSessionsByDates(date1From, date1To);



       /* assertEquals(3, sessions.size());

        assertTrue(sessions.contains(s1));
        assertTrue(sessions.contains(s2));
        assertTrue(!sessions.contains(s3));*/

    }

}

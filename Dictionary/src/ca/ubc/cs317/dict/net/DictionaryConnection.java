package ca.ubc.cs317.dict.net;

import ca.ubc.cs317.dict.exception.DictConnectionException;
import ca.ubc.cs317.dict.model.Database;
import ca.ubc.cs317.dict.model.Definition;
import ca.ubc.cs317.dict.model.MatchingStrategy;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Created by Jonatan on 2017-09-09.
 */
public class DictionaryConnection {

    private static final int DEFAULT_PORT = 2628;

    private static final int DATABASES_PRESENT = 110;
    private static final int STRATEGIES_AVAILABLE = 111;
    private static final int DEFINITIONS_RETRIEVED = 150;
    private static final int WORD_DATABASE_NAME = 151;
    private static final int MATCHES_FOUND = 152;
    private static final int OPENING_CONNECTION = 220;
    private static final int CLOSING_CONNECTION = 221;
    private static final int INVALID_DATABASE = 550;
    private static final int INVALID_STRATEGY = 551;
    private static final int NO_MATCH = 552;
    private static final int NO_DATABASES_PRESENT = 554;
    private static final int NO_STRATEGIES_AVAILABLE = 555;

    private Socket socket;
    private BufferedReader input;
    private PrintWriter output;

    private Map<String, Database> databaseMap = new LinkedHashMap<String, Database>();

    /** Establishes a new connection with a DICT server using an explicit host and port number, and handles initial
     * welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @param port Port number used by the DICT server
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host, int port) throws DictConnectionException {

        try {
            socket = new Socket(host, port);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            output = new PrintWriter(socket.getOutputStream(), true);

            Status open = Status.readStatus(input); // throws exception if not status code

            if (open.getStatusCode() != OPENING_CONNECTION) {
                throw new DictConnectionException();
            }

            // Connected to host dict.org:2628

        } catch (UnknownHostException e) {
            System.err.println("Don't know about host " + host);
            throw new DictConnectionException();
        } catch (IOException e) {
            System.err.println("Couldn't get I/O for the connection to " + host);
            throw new DictConnectionException();
        }

    }

    /** Establishes a new connection with a DICT server using an explicit host, with the default DICT port number, and
     * handles initial welcome messages.
     *
     * @param host Name of the host where the DICT server is running
     * @throws DictConnectionException If the host does not exist, the connection can't be established, or the messages
     * don't match their expected value.
     */
    public DictionaryConnection(String host) throws DictConnectionException {
        this(host, DEFAULT_PORT);
    }

    /** Sends the final QUIT message and closes the connection with the server. This function ignores any exception that
     * may happen while sending the message, receiving its reply, or closing the connection.
     *
     */
    public synchronized void close() {

        try {
            // check if socket closed already
            if (socket.isClosed() || socket == null) {
                return;
            }

            // send QUIT command
            output.println("QUIT");

            Status quit = Status.readStatus(input);

            // ignore incorrect closing connection status
            if (quit.getStatusCode() != CLOSING_CONNECTION) {
                System.err.println("Exception ignored");
            }

            // close socket
            input.close();
            output.close();
            socket.close();

        } catch (IOException e) { // thrown if connection is interrupted
            socket = null;
            System.err.println("IOException ignored.");
        } catch (DictConnectionException e) {
            socket = null;
            System.err.println("DictConnectionException ignored.");
        }

    }

    /** Requests and retrieves all definitions for a specific word.
     *
     * @param word The word whose definition is to be retrieved.
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 definitions in the first database that has a definition for the word should be used
     *                 (database '!').
     * @return A collection of Definition objects containing all definitions returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Definition> getDefinitions(String word, Database database) throws DictConnectionException {
        Collection<Definition> set = new ArrayList<>();
        getDatabaseList(); // Ensure the list of databases has been populated

        try {
            String message;
            String defineWord = "";
            Database defineDatabase = new Database("", "");
            Definition definition = new Definition("", defineDatabase);

            // check if there is a network connection

            isConnected();

            // send DEFINE command in 'DEFINE database word' format
            output.println("DEFINE " + database.getName() + " \"" + word + "\"");
            Thread.sleep(200); // waiting for server response
            Status define = Status.readStatus(input);

            // check if initial status is correct
            switch (define.getStatusCode()) {
                case DEFINITIONS_RETRIEVED:
                    while (socket.isConnected() && (message = input.readLine()) != null) {
                        if (message.equals(".")) { // keep reading the definition until period
                            set.add(definition); // add the Definition object to set
                        } else if (message.split(" ")[0].equals("151")) { // start of new definition

                            int firstQuotationMark = message.indexOf("\"") + 1;
                            int lastQuotationMark = message.indexOf("\"", firstQuotationMark);
                            int databaseStart = message.indexOf(" ", lastQuotationMark + 2);

                            // finds position of word and database within message string
                            defineWord = message.substring(firstQuotationMark, lastQuotationMark);
                            defineDatabase = databaseMap.get(message.substring(lastQuotationMark + 2, databaseStart));

                            definition = new Definition(defineWord, defineDatabase); // create new definition object

                        } else if (message.split(" ")[0].equals("250")) {
                            break; // we stop collecting when status code indicates all definitions have been sent
                        } else {
                            definition.appendDefinition(message); // append line to definition
                        }
                    }
                    break;
                case INVALID_DATABASE: // code for invalid database
                    throw new DictConnectionException("Invalid database, use \"SHOW DB\" for list of databases");
                case NO_MATCH: // return empty set if no match (nothing added to set)
                    break;
                default:
                    throw new DictConnectionException("This should not be thrown");
            }
        } catch (IOException e) { // thrown if connection is interrupted
            System.err.println("Couldn't get I/O for the connection to server");
            throw new DictConnectionException();
        } catch (InterruptedException e) {
            throw new DictConnectionException();
        }

        return set;
    }

    /** Requests and retrieves a list of matches for a specific word pattern.
     *
     * @param word     The word whose definition is to be retrieved.
     * @param strategy The strategy to be used to retrieve the list of matches (e.g., prefix, exact).
     * @param database The database to be used to retrieve the definition. A special database may be specified,
     *                 indicating either that all regular databases should be used (database name '*'), or that only
     *                 matches in the first database that has a match for the word should be used (database '!').
     * @return A set of word matches returned by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<String> getMatchList(String word, MatchingStrategy strategy, Database database) throws DictConnectionException {
        Set<String> set = new LinkedHashSet<>();

        try {
            String message;
            String matchWord;

            // check if there is a network connection
            isConnected();

            // send MATCH command in 'MATCH database strategy word' format
            output.println("MATCH " + database.getName() + " " + strategy.getName() + " \"" + word + "\"");
            Thread.sleep(200); // waiting for server response
            Status match = Status.readStatus(input);

            // check if initial status is correct
            switch (match.getStatusCode()) {
                case MATCHES_FOUND: // successful matches found status
                    while (socket.isConnected() && (message = input.readLine()) != null) {
                        if (message.equals(".")) { // keep reading matches until period
                            isCompleted(input); // check if the completion code is read
                            break;
                        }
                        matchWord = message.substring(message.indexOf("\"") + 1, message.lastIndexOf("\""));
                        set.add(matchWord); // adds matching word to set
                    }
                    break;
                case INVALID_DATABASE: // code for invalid database
                    throw new DictConnectionException("Invalid database, use \"SHOW DB\" for list of databases");
                case INVALID_STRATEGY:
                    throw new DictConnectionException("Invalid strategy, use \"SHOW STRAT\" for a list of strategies");
                case NO_MATCH: // return empty set if no match (nothing added to set)
                    break;
                default:
                    throw new DictConnectionException("This should not be thrown");
            }

        } catch (IOException e) { // thrown if connection is interrupted
            System.err.println("Couldn't get I/O for the connection to server");
            throw new DictConnectionException();
        } catch (InterruptedException e) {
            throw new DictConnectionException();
        }

        return set;
    }

    /** Requests and retrieves a list of all valid databases used in the server. In addition to returning the list, this
     * method also updates the local databaseMap field, which contains a mapping from database name to Database object,
     * to be used by other methods (e.g., getDefinitionMap) to return a Database object based on the name.
     *
     * @return A collection of Database objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Collection<Database> getDatabaseList() throws DictConnectionException {

        if (!databaseMap.isEmpty()) return databaseMap.values();

        try {
            String databaseInfo;
            String name;
            String description;

            // check if there is a network connection
            isConnected();

            // send 'SHOW DB' command
            output.println("SHOW DB");

            Status showDB = Status.readStatus(input);

            // check if initial status is correct
            switch (showDB.getStatusCode()) {
                case DATABASES_PRESENT: // successful databases present status
                    while (socket.isConnected() && (databaseInfo = input.readLine()) != null) {
                        if (databaseInfo.equals(".")) {
                            isCompleted(input); // check if the completion code is read
                            break;
                        }
                        name = databaseInfo.substring(0, databaseInfo.indexOf(" "));
                        description = databaseInfo.substring(databaseInfo.indexOf("\"") + 1, databaseInfo.lastIndexOf("\""));
                        databaseMap.put(name, new Database(name, description)); // maps the name to the Database object in databaseMap
                    }
                    break;
                case NO_DATABASES_PRESENT: // return empty map if no databases found
                    break;
                default:
                    throw new DictConnectionException();

            }

        } catch(IOException e){ // thrown if connection is interrupted
            System.err.println("Couldn't get I/O for the connection to server");
            throw new DictConnectionException();
        }

        return databaseMap.values();
    }

    /** Requests and retrieves a list of all valid matching strategies supported by the server.
     *
     * @return A set of MatchingStrategy objects supported by the server.
     * @throws DictConnectionException If the connection was interrupted or the messages don't match their expected value.
     */
    public synchronized Set<MatchingStrategy> getStrategyList() throws DictConnectionException {
        Set<MatchingStrategy> set = new LinkedHashSet<>();

        try {
            String message;
            String name;
            String description;

            // check if there is a network connection
            isConnected();

            // send 'SHOW STRAT' command
            output.println("SHOW STRAT");

            Status showStrat = Status.readStatus(input);

            // check if initial status is correct
            switch (showStrat.getStatusCode()) {
                case STRATEGIES_AVAILABLE: // successful strategies available status
                    while (socket.isConnected() && ((message = input.readLine()) != null)) {
                        if (message.equals(".")) {
                            isCompleted(input); // check if the completion code is read
                            break;
                        }
                        name = message.substring(0, message.indexOf(" "));
                        description = message.substring(message.indexOf("\"") + 1, message.lastIndexOf("\""));
                        set.add(new MatchingStrategy(name, description)); // adds the MatchingStrategy object to set
                    }
                    break;
                case NO_STRATEGIES_AVAILABLE: // return empty set if no strategies found
                    break;
                default:
                    throw new DictConnectionException();
            }

        } catch (IOException e) { // thrown if connection is interrupted
            System.err.println("Couldn't get I/O for the connection to server");
            throw new DictConnectionException();
        }

        return set;
    }

    /** Checks if the socket is connected.
     *
     * @throws DictConnectionException If the socket is not connected.
     */
    public synchronized void isConnected() throws DictConnectionException {
        if (!socket.isConnected()) {
            throw new DictConnectionException();
        }
    }

    /** Checks if the command returns an OK/valid completion status.
     *
     * @param input The BufferedReader for the socket's input stream.
     * @throws DictConnectionException If the completion status code is not 2xx.
     */
    public synchronized void isCompleted(BufferedReader input) throws DictConnectionException {
        Status completion = Status.readStatus(input);
        if (completion.getStatusType() != Status.COMPLETION_REPLY) {
            throw new DictConnectionException();
        }
    }
}

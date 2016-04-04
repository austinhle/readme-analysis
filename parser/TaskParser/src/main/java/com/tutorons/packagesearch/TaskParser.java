package com.tutorons.packagesearch;

import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.ling.Tag;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.CoreLabelTokenFactory;
import edu.stanford.nlp.process.Morphology;
import edu.stanford.nlp.process.PTBTokenizer;
import edu.stanford.nlp.process.Tokenizer;
import edu.stanford.nlp.process.TokenizerFactory;
import edu.stanford.nlp.trees.EnglishGrammaticalRelations;
import edu.stanford.nlp.trees.EnglishGrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TypedDependency;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.sql.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.examples.HtmlToPlainText;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import org.json.JSONObject;


public class TaskParser {
  public enum Filter {
    VERB_ALL_NOUNS,
    VERB_ONE_NOUN,
    VERB,
    VERB_ONE_NOUN_SAME_QUERY
  }

  // Regular expressions for identifying sentences and replacing certain HTML tags.

  // Regular expression for URL matching borrowed from http://stackoverflow.com/questions/15518845/how-to-validate-url-in-java-using-regex
  private final static String URL_REGEX = "(<)?(@)?(href=')?(HREF=')?(HREF=\")?(href=\")?(http(s)?)?(://)?[a-zA-Z_0-9\\-]+(\\.\\w[a-zA-Z_0-9\\-]+)+(/[#&\\n\\-=?\\+\\%/\\.\\w]+)?(>)?";
  private final static String TT_REGEX = "<tt>.*</tt>";
  private final static String PUNCT_REGEX = ".*\\p{Punct}";

  // List of part-of-speech tags related to verbs and their conjugations.
  private final static List<String> verbPOSList = Arrays.asList(
    "VB", "VBD", "VBG", "VBN", "VBP", "VBZ"
  );

  // List of part-of-speech tags related to nouns and their various forms.
  private final static List<String> nounPOSList = Arrays.asList(
    "NN", "NNP", "NNPS", "NNS"
  );

  // Task mode variables
  private final static String NO_TRUNCATION = "NO_TRUNCATION";
  private final static String ONE_OBJ = "ONE_OBJ";
  private final static String ONE_PREP_ONE_OBJ = "ONE_PREP_ONE_OBJ";
  private final static List<String> taskModes = Arrays.asList(
    NO_TRUNCATION, ONE_PREP_ONE_OBJ, ONE_OBJ
  );

  // PostgreSQL variables
  private final static String DB_CREDENTIALS = "credentials.json";

  // Stanford NLP parser variables
  private final static String PCG_MODEL = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
  private final static TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
  private final static LexicalizedParser parser = LexicalizedParser.loadModel(PCG_MODEL);

  private static Set<Query> queries = new HashSet<Query>();

  public static void main(String[] args) {
    
    System.out.println("Starting main...");

    String credentialsPath;
    if (args.length >= 1) {
        credentialsPath = args[0];
    } else {
        credentialsPath = DB_CREDENTIALS;
    }

    TaskParser mp = new TaskParser();

    // Load credentials
    String text;
    try {
      text = new String(Files.readAllBytes(Paths.get(credentialsPath)), StandardCharsets.UTF_8);
    } catch (IOException e) {
      System.out.println(e);
      return;
    }

    JSONObject obj = new JSONObject(text);
    String username = obj.getString("dbusername");
    String password = obj.getString("dbpassword");
    String dbUrl = "jdbc:postgresql://" + obj.getString("host") + "/" + obj.getString("database");

    if (username.equals("") || password.equals("")) {
      System.err.println("Empty credentials were loaded. Please check credentials file.");
      return;
    }

    System.out.println("Successfully loaded credentials.");

    int computeIndex;
    int searchResultContentId = -1;
    String title = "";
    String url = "";
    String content = "";

    try {
      System.out.println("Attempting to connect to database...");
      Connection db = DriverManager.getConnection(dbUrl, username, password);
      System.out.println("Successfully connected to database.");

      Statement st = db.createStatement();

      // All table initialization takes place in ORM code elsewhere
      // for elegance and consistency with the rest of the code base:
      // https://github.com/andrewhead/Package-Qualifiers

      System.out.println("Getting index of this round of computation.");
      st = db.createStatement();
      ResultSet rs = st.executeQuery("SELECT MAX(compute_index) FROM task");
      int lastComputeIndex;
      if (rs.next()) {
        lastComputeIndex = rs.getInt(1);
        if (rs.wasNull()) {
          lastComputeIndex = -1;
        }
      } else {
        lastComputeIndex = -1;
      }
      computeIndex = lastComputeIndex + 1;
      System.out.println("Index of this computation: " + computeIndex);

      st = db.createStatement();
      rs = st.executeQuery("SELECT query FROM query WHERE fetch_index = 14 AND query LIKE '%mongoose%' GROUP BY query");
      System.out.println("Retrieved queries for task filtering.");

      int queryIndex = 0;

      // Loop through all queries stored in the database.
      while (rs.next()) {
        String query = rs.getString(1);
        Tree queryTree = parse(query);
        List<Tree> queryPreorder = queryTree.preOrderNodeList();

        Set<String> queryWords = new HashSet<String>();

        for (Tree t : queryPreorder) {
          if (t.isLeaf()) {
            String word = t.label().value().toString();
            String wordLabel = t.parent(queryTree).label().value().toString();
            word = Morphology.lemmaStatic(word, wordLabel, false);
            queryWords.add(word);
          }
        }

        queries.add(new Query(queryWords));
      }

      System.out.println("Created set of queries for task filtering.");

      int outIndex;

      /* Code segment below is for extracting tasks from the searchresultcontent table, which
       * stores pre-fetched tutorial and documentation content.
      */
      // st = db.createStatement();
      // rs = st.executeQuery("SELECT searchresultcontent.id, title, snippet, url, content FROM searchresultcontent JOIN searchresult ON search_result_id = searchresult.id ORDER BY title DESC");
      // System.out.println("Retrieved search result content to perform task extraction on.");
      //
      // while (rs.next()) {
      //   searchResultContentId = rs.getInt(1);
      //   title = rs.getString(2);
      //   url = rs.getString(4);
      //   content = rs.getString(5);
      //
      //   mp.extractTasks(db, computeIndex, searchResultContentId, title, url, content, outIndex, filterMode);
      //
      //   outIndex++;
      // }
      // rs.close();
      // st.close();

      searchResultContentId = -1; // Unused
      title = "Object Modeling in Node.js with Mongoose | Heroku Dev Center";
      url = "https://devcenter.heroku.com/articles/nodejs-mongoose#mongodb-connectors";
      content = ""; // Unused
      outIndex = -1; // Unused


      // TODO: Shouldn't go through all possible filter modes every time. Should be specified
      // by command-line argument to program.
      for (Filter filterMode : Filter.values()) {
        mp.extractTasks(db, computeIndex, searchResultContentId, title, url, content, outIndex, filterMode);
        computeIndex++;
      }

    } catch (SQLException e) {
      System.err.println(e);
    }
  }

  public void extractTasks(Connection db, int computeIndex, int searchResultContentId, String title, String url, String content, int outIndex, Filter filterMode) {
    // Document doc = Jsoup.parse(content);
    Document doc = null;
    try {
      doc = Jsoup.connect(url).get();
    } catch (IOException e) {
      System.err.println("Failed to connect to Mongoose documentation to retrieve content: " + e);
    }

    Element docBody = doc.body();

    // Remove code blocks from document body.
    Elements preTags = docBody.getElementsByTag("pre");
    for (Element e : preTags) { e.empty(); }
    Elements codeTags = docBody.getElementsByTag("code");
    for (Element e : codeTags) { e.empty(); }
    Elements blockquoteTags = docBody.getElementsByTag("blockquote");
    for (Element e : blockquoteTags) { e.empty(); }

    // Punctuate ends of paragraphs with a period if it doesn't already have punctuation.
    Elements paragraphTags = docBody.getElementsByTag("p");
    for (Element e : paragraphTags) {
      if (!Pattern.matches(PUNCT_REGEX, e.text())) {
        e.text(e.text() + ".");
      }
    }

    HtmlToPlainText htpt = new HtmlToPlainText();

    // Remove URLs and in-line code (<tt> tags) from plaintext body.
    String plaintext = htpt.getPlainText(docBody).replaceAll(URL_REGEX, "URL");
    plaintext = plaintext.replaceAll(TT_REGEX, "CODE-TT");

    String[] sentences = plaintext.split(Pattern.quote("."));

    System.out.println(String.format("=== Extracting content #%d ===", outIndex));
    System.out.println("= Title: " + title);
    System.out.println("= URL: " + url);

    Set<String> keptTasks = new HashSet<String>();
    int numTasksTotal = 0;
    int numTasksAfterFiltering = 0;

    for (String sentence : sentences) {
      if (sentence.trim().length() == 0) {
        continue;
      }

      // Clean up sentences before parsing.
      sentence = sentence.replaceAll("\n", " ").replaceAll("\t", " ");

      Tree tree = parse(sentence);

      Set<Task> tasks = new HashSet<Task>();
      for (String taskMode : taskModes) {
        findTasksFromTree(tree, tree, tasks, taskMode);
        numTasksTotal += tasks.size();

        filterTasksByQueries(tasks, filterMode);

        for (Task task : tasks) {
          numTasksAfterFiltering++;
          keptTasks.add(task.taskString);
          saveTask(db, task, taskMode, computeIndex, searchResultContentId);
        }
      }
    }

    System.out.println("Number of tasks before filtering: " + numTasksTotal);
    System.out.println("Remaining number of tasks after filtering: " + numTasksAfterFiltering);
    for (String kt : keptTasks) {
      System.out.println(kt);
    }
    System.out.print("\n\n");
  }

  private void findTasksFromTree(Tree root, Tree t, Set<Task> tasks, String taskMode) {
    if (taskMode.equals(NO_TRUNCATION)) {
      findTasksNoTruncation(root, t, tasks);
    } else if (taskMode.equals(ONE_OBJ)) {
      findTasksOneObj(root, t, tasks);
    } else {
      findTasksOnePrepOneObj(root, t, tasks);
    }
  }

  // This method extracts tasks by splitting verb phrases at the start of each VP part
  // of speech it finds.
  private void findTasksNoTruncation(Tree root, Tree t, Set<Task> tasks) {
    Label label = t.label();
    String val = label.value();

    // A preorder traversal of the tree here will result in a list of trees
    // that contain the words of the verb phrase in the correct order.
    // The words themselves are contained in the trees that are leaves.
    List<Tree> preorder = t.preOrderNodeList();

    StringJoiner taskString = new StringJoiner(" ");
    StringJoiner lemmatizedTaskString = new StringJoiner(" ");
    String lemmatizedVerb = "";

    Set<String> lemmatizedNouns = new HashSet<String>();

    int numWords = 0;
    for (Tree preTree : preorder) {
      if (preTree.isLeaf()) { // This node represents an actual word in the sentence.
        String word = preTree.label().value().toString();
        String wordLabel = preTree.parent(root).label().value().toString();
        if (wordLabel.equals("VP")) {
          if (numWords > 1) {
            System.out.println("Task found: " + taskString.toString());
            // System.out.println("Lemmatized task found: " + lemmatizedTaskString.toString());
            tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));

            taskString = new StringJoiner(" ");
            lemmatizedTaskString = new StringJoiner(" ");
            lemmatizedVerb = "";
            lemmatizedNouns = new HashSet<String>();
            numWords = 0;
          }
        }
        numWords++;

        // Add word to task string before we potentially lemmatize it.
        taskString.add(word);

        if (verbPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedVerb = word;
        } else if (nounPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedNouns.add(word);
        }

        lemmatizedTaskString.add(word);
      }
    }

    tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));
  }

  // This method extracts tasks by splitting verb phrases into individual tasks as soon as it finds
  // a phrase that involves both a verb and a single object, excluding prepositions and other
  // modifiers.
  private void findTasksOneObj(Tree root, Tree t, Set<Task> tasks) {
    Label label = t.label();
    String val = label.value();

    // A preorder traversal of the tree here will result in a list of trees
    // that contain the words of the verb phrase in the correct order.
    // The words themselves are contained in the trees that are leaves.
    List<Tree> preorder = t.preOrderNodeList();

    StringJoiner taskString = new StringJoiner(" ");
    StringJoiner lemmatizedTaskString = new StringJoiner(" ");
    String lemmatizedVerb = "";

    Set<String> lemmatizedNouns = new HashSet<String>();

    boolean foundNounPhrase = false;
    int numWords = 0;

    for (Tree preTree : preorder) {
      if (preTree.isLeaf()) { // This node represents an actual word in the sentence.
        String word = preTree.label().value().toString();
        String wordLabel = preTree.parent(root).label().value().toString();
        if (wordLabel.equals("VP") || wordLabel.equals("NP")) {
          if (numWords > 1 && !lemmatizedVerb.equals("") && foundNounPhrase) {
            System.out.println("Task found: " + taskString.toString());
            // System.out.println("Lemmatized task found: " + lemmatizedTaskString.toString());
            tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));

            taskString = new StringJoiner(" ");
            lemmatizedTaskString = new StringJoiner(" ");
            lemmatizedVerb = "";
            foundNounPhrase = false;
            lemmatizedNouns = new HashSet<String>();
            numWords = 0;
          }
        }
        numWords++;

        // Add word to task string before we potentially lemmatize it.
        taskString.add(word);

        if (verbPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedVerb = word;
        } else if (nounPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedNouns.add(word);
          foundNounPhrase = true;
        }

        lemmatizedTaskString.add(word);
      }
    }

    tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));
  }

  // This method extracts tasks by splitting verb phrases into individual tasks as soon as it finds
  // a phrase that involves both a verb and a single object and a single propositional phrase,
  // excluding other modifiers.
  private void findTasksOnePrepOneObj(Tree root, Tree t, Set<Task> tasks) {
    Label label = t.label();
    String val = label.value();

    // A preorder traversal of the tree here will result in a list of trees
    // that contain the words of the verb phrase in the correct order.
    // The words themselves are contained in the trees that are leaves.
    List<Tree> preorder = t.preOrderNodeList();

    StringJoiner taskString = new StringJoiner(" ");
    StringJoiner lemmatizedTaskString = new StringJoiner(" ");
    String lemmatizedVerb = "";

    Set<String> lemmatizedNouns = new HashSet<String>();

    boolean foundNounPhrase = false;
    boolean foundPrepPhrase = false;
    int numWords = 0;

    for (Tree preTree : preorder) {
      if (preTree.isLeaf()) { // This node represents an actual word in the sentence.
        String word = preTree.label().value().toString();
        String wordLabel = preTree.parent(root).label().value().toString();
        if (wordLabel.equals("VP") || wordLabel.equals("NP") || wordLabel.equals("PP")) {
          if (numWords > 1 && !lemmatizedVerb.equals("") && foundNounPhrase && foundPrepPhrase) {
            System.out.println("Task found: " + taskString.toString());
            // System.out.println("Lemmatized task found: " + lemmatizedTaskString.toString());
            tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));

            taskString = new StringJoiner(" ");
            lemmatizedTaskString = new StringJoiner(" ");
            lemmatizedVerb = "";
            foundNounPhrase = false;
            foundPrepPhrase = false;
            lemmatizedNouns = new HashSet<String>();
            numWords = 0;
          }
        }
        numWords++;

        // Add word to task string before we potentially lemmatize it.
        taskString.add(word);

        if (verbPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedVerb = word;
        } else if (nounPOSList.contains(wordLabel)) {
          word = Morphology.lemmaStatic(word, wordLabel, false);
          lemmatizedNouns.add(word);
          foundNounPhrase = true;
        } else if (wordLabel.equals("PP")) {
          foundPrepPhrase = true;
        }

        lemmatizedTaskString.add(word);
      }
    }

    tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));
  }

  private void saveTask(Connection db, Task task, String taskMode, int computeIndex, int searchResultContentId) {
    try {
        ResultSet rs;
        int taskId, nounId, verbId;

        // Create a new task
        PreparedStatement st = db.prepareStatement(
            "INSERT INTO task (compute_index, date, task, mode, search_result_content_id) VALUES (?, now(), ?, ?, ?) returning id"
        );
        st.setInt(1, computeIndex);
        st.setString(2, task.taskString);
        st.setString(3, taskMode);
        st.setInt(4, searchResultContentId);
        rs = st.executeQuery();
        rs.next();
        taskId = rs.getInt(1);

        // Save links to its verb and nouns
        PreparedStatement verbLinkStatement = db.prepareStatement(
            "INSERT INTO taskverb (task_id, verb_id) VALUES (?, ?)"
        );
        PreparedStatement nounLinkStatement = db.prepareStatement(
            "INSERT INTO tasknoun (task_id, noun_id) VALUES (?, ?)"
        );
        for (String noun : task.lemmatizedNouns) {
            nounId = get_or_create(db, "noun", "noun", noun);
            nounLinkStatement.setInt(1, taskId);
            nounLinkStatement.setInt(2, nounId);
            nounLinkStatement.executeUpdate();
        }
        verbId = get_or_create(db, "verb", "verb", task.lemmatizedVerb);
        verbLinkStatement.setInt(1, taskId);
        verbLinkStatement.setInt(2, verbId);
        verbLinkStatement.executeUpdate();

    } catch (SQLException e) {
        System.err.println("SQLException (" + e + ")");
    }

  }

  /**
   * This is a simplified analog to the 'get_or_create' methods on many ORMs,
   * except that the value for only one field can be passed in the 'columnName'
   * and 'value' parameters, and its value can only be a String.
   */
  private int get_or_create(Connection db, String tableName, String columnName, String value) throws SQLException {
    int id;
    int rowCount = 0;
    ResultSet rs;

    // If tableName or columnName are ever defined outside of this program,
    // we will need to check them against some whitelist
    PreparedStatement countStatement = db.prepareStatement(
        "SELECT COUNT(*) FROM " + tableName + " WHERE " + columnName + " = ?"
    );
    PreparedStatement selectStatement = db.prepareStatement(
        "SELECT id FROM " + tableName + " WHERE " + columnName  +  " = ?"
    );
    PreparedStatement insertStatement = db.prepareStatement(
        "INSERT INTO " + tableName + " (" + columnName + ") VALUES (?) returning id"
    );

    countStatement.setString(1, value);
    rs = countStatement.executeQuery();
    rs.next();
    rowCount = rs.getInt(1);

    // If a record exists with this value, return its ID.
    // Otherwise, create a new record for the value, and return its ID.
    if (rowCount > 0) {
      selectStatement.setString(1, value);
      rs = selectStatement.executeQuery();
    } else {
      insertStatement.setString(1, value);
      rs = insertStatement.executeQuery();
    }
    rs.next();
    id = rs.getInt(1);

    return id;
  }

  private void filterTasksByQueries(Set<Task> tasks, Filter filterMode) {
    Map<Task, Boolean> toRemove = new HashMap<Task, Boolean>();

    for (Task task : tasks) {
      if (taskSimilarToSomeQuery(task, filterMode)) {
        toRemove.put(task, false);
      } else {
        toRemove.put(task, true);
      }
    }

    for (Task task : toRemove.keySet()) {
      if (toRemove.get(task)) {
        tasks.remove(task);
      }
    }
  }

  private boolean taskSimilarToSomeQuery(Task task, Filter filterMode) {
    String lemmatizedTaskVerb = task.lemmatizedVerb;
    Set<String> lemmatizedNouns = task.lemmatizedNouns;

    switch (filterMode) {
      case VERB_ONE_NOUN:
        // The task's verb must appear in at least one of the queries.
        for (Query q : queries) {
          if (!q.queryWords.contains(lemmatizedTaskVerb)) {
            return false;
          }
        }


        // In addition, at least one of the nouns in the task must appear in at least one of
        // the queries.
        for (Query q : queries) {
          for (String noun : lemmatizedNouns) {
            if (q.queryWords.contains(noun)) {
              System.out.println("--- Found a task!");
              return true;
            }
          }
        }


        return false;

      case VERB_ALL_NOUNS:
        // The task's verb must appear in at least one of the queries.
        for (Query q : queries) {
          if (!q.queryWords.contains(lemmatizedTaskVerb)) {
            return false;
          }
        }

        // In addition, all of the nouns in the task must appear in at least one of the queries.
        for (Query q : queries) {
          for (String noun : lemmatizedNouns) {
            if (!q.queryWords.contains(noun)) {
              return false;
            }
          }
        }

        System.out.println("--- Found a task!");
        return true;

      case VERB:
        // The task's verb must appear in at least one of the queries.
        for (Query q : queries) {
          if (q.queryWords.contains(lemmatizedTaskVerb)) {
            return true;
          }
        }
        return false;

      case VERB_ONE_NOUN_SAME_QUERY:
        // The task's verb and at least one of its nouns must appear in at least one of the queries.
        // In addition, the verb and noun must come from the same query.
        for (Query q : queries) {
          boolean queryContainsNoun = false;
          for (String noun : lemmatizedNouns) {
            if (q.queryWords.contains(noun)) {
              queryContainsNoun = true;
            }
          }
          if (q.queryWords.contains(lemmatizedTaskVerb) && queryContainsNoun) {
            System.out.println("--- Found a task!");
            return true;
          }
        }

        return false;

      default:
        return false;
    }
  }

  public static Tree parse(String str) {
    List<CoreLabel> tokens = tokenize(str);
    Tree tree = parser.apply(tokens);
    return tree;
  }

  private static List<CoreLabel> tokenize(String str) {
    Tokenizer<CoreLabel> tokenizer = tokenizerFactory.getTokenizer(new StringReader(str));
    return tokenizer.tokenize();
  }

  private class Task {
    public String taskString;
    public String lemmatizedTaskString;
    public String lemmatizedVerb;
    public Set<String> lemmatizedNouns;

    public Task(String taskString, String lemmatizedTaskString, String lemmatizedVerb, Set<String> lemmatizedNouns) {
      this.taskString = taskString;
      this.lemmatizedTaskString = lemmatizedTaskString;
      this.lemmatizedVerb = lemmatizedVerb;
      this.lemmatizedNouns = lemmatizedNouns;
    }
  }

  private static class Query {
    public Set<String> queryWords;

    public Query(Set<String> queryWords) {
      this.queryWords = queryWords;
    }
  }
}

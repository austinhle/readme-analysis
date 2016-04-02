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

import java.io.File;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.BufferedReader;
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

public class MyParser {
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

  // PostgreSQL variables
  private final static String DB_URL = "jdbc:postgresql://clarence.eecs.berkeley.edu/fetcher";
  private final static String DB_CREDENTIALS = "credentials.txt";

  // Stanford NLP parser variables
  private final static String PCG_MODEL = "edu/stanford/nlp/models/lexparser/englishPCFG.ser.gz";
  private final static TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
  private final static LexicalizedParser parser = LexicalizedParser.loadModel(PCG_MODEL);

  private static String user = "";
  private static String password = "";

  private static Set<String> lemmatizedQueryWords = new HashSet<String>();

  public static void main(String[] args) {
    System.out.println("Starting main...");

    MyParser mp = new MyParser();

    // Load credentials
    try {
      BufferedReader br = new BufferedReader(new FileReader(new File(DB_CREDENTIALS)));
      user = br.readLine();
      password = br.readLine();
      br.close();
    } catch (IOException e) {
      System.out.println(e);
    }

    if (user.equals("") || password.equals("")) {
      System.out.println("Empty credentials were loaded. Please check credentials file.");
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
      Connection db = DriverManager.getConnection(DB_URL, user, password);
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

      while (rs.next()) {
        String query = rs.getString(1);
        Tree queryTree = parse(query);
        List<Tree> queryPreorder = queryTree.preOrderNodeList();

        for (Tree t : queryPreorder) {
          if (t.isLeaf()) {
            String word = t.label().value().toString();
            String wordLabel = t.parent(queryTree).label().value().toString();
            lemmatizedQueryWords.add(Morphology.lemmaStatic(word, wordLabel, false));
          }
        }
      }

      System.out.println("Created set of lemmatized query words for task filtering.");

      st = db.createStatement();
      rs = st.executeQuery("SELECT searchresultcontent.id, title, snippet, url, content FROM searchresultcontent JOIN searchresult ON search_result_id = searchresult.id ORDER BY title DESC");
      System.out.println("Retrieved search result content to perform task extraction on.");

      int outIndex = 0;

      while (rs.next())
      {
        searchResultContentId = rs.getInt(1);
        title = rs.getString(2);
        url = rs.getString(4);
        content = rs.getString(5);

        System.out.println("Title: " + title);
        System.out.println("URL: " + url);

        mp.extractTasks(db, computeIndex, searchResultContentId, title, url, content, outIndex);

        outIndex++;
      }
      rs.close();
      st.close();
    } catch (SQLException e) {
      System.err.println(e);
    }
  }

  public void extractTasks(Connection db, int computeIndex, int searchResultContentId, String title, String url, String content, int outIndex) {

    Document doc = Jsoup.parse(content);
    Element docBody = doc.body();

    // Remove code blocks from document body.
    Elements preTags = docBody.getElementsByTag("pre");
    for (Element e : preTags) {
      e.empty();
    }
    Elements codeTags = docBody.getElementsByTag("code");
    for (Element e : codeTags) {
      e.empty();
    }
    Elements blockquoteTags = docBody.getElementsByTag("blockquote");
    for (Element e : blockquoteTags) {
      e.empty();
    }

    // Punctuate ends of paragraphs if it doesn't already have punctuation.
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
      findTasksFromTree(tree, tree, tasks);
      numTasksTotal += tasks.size();

      filterTasksByQueries(tasks);

      for (Task task : tasks) {
        numTasksAfterFiltering++;
        keptTasks.add(task.taskString);
        saveTask(db, task, computeIndex, searchResultContentId);
      }
    }

    System.out.println("Number of tasks before filtering: " + numTasksTotal);
    System.out.println("Remaining number of tasks after filtering: " + numTasksAfterFiltering);
    for (String kt : keptTasks) {
      System.out.println(kt);
    }
    System.out.print("\n\n");
  }

  private void saveTask(Connection db, Task task, int computeIndex, int searchResultContentId) {

    try {

        ResultSet rs;
        int taskId, nounId, verbId;

        // Create a new task
        PreparedStatement st = db.prepareStatement(
            "INSERT INTO task (compute_index, date, task, search_result_content_id) VALUES (?, now(), ?, ?) returning id"
        );
        st.setInt(1, computeIndex);
        st.setString(2, task.taskString);
        st.setInt(3, searchResultContentId);
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

  private void findTasksFromTree(Tree root, Tree t, Set<Task> tasks) {
    if (t.isLeaf()) {
      return;
    } else {
      Label label = t.label();
      String val = label.value();
      if (val.toString().equals("VP")) {
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
            if (wordLabel.equals("PP") || wordLabel.equals("CC") || wordLabel.equals("VP")) {
              // Use PP and CC parts of speech (prep phrase and conjunction) as delimiters.
              if (numWords > 1) {
                // System.out.println("Task found: " + taskString.toString());
                // System.out.println("Lemmatized task found: " + lemmatizedTaskString.toString());
                tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));

                // Recursively check children.
                Tree[] children = t.children();
                for (Tree child : children) {
                  findTasksFromTree(root, child, tasks);
                }
                return;
              }
            } else {
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
        }

        tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, lemmatizedNouns));
      }
      // Recursively check children.
      Tree[] children = t.children();
      for (Tree child : children) {
        findTasksFromTree(root, child, tasks);
      }
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
      rs.next();
      id = rs.getInt(1);
    } else {
      insertStatement.setString(1, value);
      rs = insertStatement.executeQuery();
      rs.next();
      id = rs.getInt(1);
    }

    return id;
    
  }

  private void filterTasksByQueries(Set<Task> tasks) {
    Map<Task, Boolean> toRemove = new HashMap<Task, Boolean>();

    for (Task task : tasks) {
      if (taskSimilarToSomeQuery(task)) {
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

  private boolean taskSimilarToSomeQuery(Task task) {
    String lemmatizedTaskVerb = task.lemmatizedVerb;
    Set<String> lemmatizedNouns = task.lemmatizedNouns;

    // The task's verb must appear in at least one of the queries.
    if (!lemmatizedQueryWords.contains(lemmatizedTaskVerb)) {
      return false;
    }

    // In addition, at least one of the nouns in the task must appear in at least one of
    // the queries.
    for (String noun : lemmatizedNouns) {
      if (lemmatizedQueryWords.contains(noun)) {
        System.out.println("--- Found a task!");
        return true;
      }
    }

    return false;
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
}

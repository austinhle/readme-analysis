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
import java.io.BufferedWriter;
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
  private final TokenizerFactory<CoreLabel> tokenizerFactory = PTBTokenizer.factory(new CoreLabelTokenFactory(), "invertible=true");
  private final LexicalizedParser parser = LexicalizedParser.loadModel(PCG_MODEL);

  private static String user = "";
  private static String password = "";

  public static void main(String[] args) {
    MyParser mp = new MyParser();

    // Load credentials
    try {
      BufferedReader br = new BufferedReader(new FileReader(new File(DB_CREDENTIALS)));
      user = br.readLine();
      password = br.readLine();
      br.close();
    } catch (IOException e) {
      System.err.println(e);
    }

    if (user.equals("") || password.equals("")) {
      System.err.println("Empty credentials were loaded. Please check credentials file.");
      return;
    }

    String title = "";
    String url = "";
    String content = "";

    try {
      Connection db = DriverManager.getConnection(DB_URL, user, password);
      Statement st = db.createStatement();
      ResultSet rs = st.executeQuery("SELECT title, snippet, url, content FROM searchresultcontent JOIN searchresult ON search_result_id = searchresult.id LIMIT 3");

      int outIndex = 0;

      while (rs.next())
      {
        title = rs.getString(1);
        url = rs.getString(3);
        content = rs.getString(4);

        System.out.println("Title: " + title);
        System.out.println("Snippet: " + rs.getString(2));
        System.out.println("URL: " + url);
        System.out.println("Content length: " + content.length());

        try {
          mp.extractTasks(title, url, content, outIndex);
        } catch (IOException e) {
          System.err.println(e);
        }

        outIndex++;
      }
      rs.close();
      st.close();
    } catch (SQLException e) {
      System.err.println(e);
    }
  }

  public void extractTasks(String title, String url, String content, int outIndex) throws IOException {
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

    File file = new File(String.format("output%d.txt", outIndex));
    BufferedWriter bw = new BufferedWriter(new FileWriter(file));

    bw.write("=== Title ===\n");
    bw.write(title);
    bw.write("\n");
    bw.write("=== URL ===\n");
    bw.write(url);
    bw.write("\n\n");

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
        bw.write(task.taskString);
        bw.write("\n");
      }
    }

    System.out.println("Number of tasks before filtering: " + numTasksTotal);
    System.out.println("Remaining number of tasks after filtering: " + numTasksAfterFiltering);

    bw.close();
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

        Set<String> nouns = new HashSet<String>();

        int numWords = 0;
        for (Tree preTree : preorder) {
          if (preTree.isLeaf()) { // This node represents an actual word in the sentence.
            Tree parent = preTree.parent(root);
            String word = preTree.label().value().toString();
            taskString.add(word);

            // This word is a verb and should be lemmatized.
            String parentLabel = parent.label().value().toString();
            if (verbPOSList.contains(parentLabel)) {
              word = Morphology.lemmaStatic(word, parentLabel, false);
              lemmatizedVerb = word;
            }
            lemmatizedTaskString.add(word);

            // This word is a noun and should be noted for later task filtering.
            if (nounPOSList.contains(parentLabel)) {
              nouns.add(word);
            }

            numWords++;
          }
        }

        if (numWords > 1) {
          // System.out.println("Task found: " + taskString.toString());
          // System.out.println("Lemmatized task found: " + lemmatizedTaskString.toString());
          tasks.add(new Task(taskString.toString(), lemmatizedTaskString.toString(), lemmatizedVerb, nouns));
        }
      }

      // Recursively check children regardless of whether or not we found a verb phrase
      // at this node.
      Tree[] children = t.children();
      for (Tree child : children) {
        findTasksFromTree(root, child, tasks);
      }
    }
  }

  private void filterTasksByQueries(Set<Task> tasks) {
    try {
      Connection db = DriverManager.getConnection(DB_URL, user, password);
      Statement st = db.createStatement();
      ResultSet rs = st.executeQuery("SELECT query FROM query WHERE fetch_index = 14 AND query LIKE '%mongoose%' GROUP BY query");

      String query = "";

      Map<Task, Boolean> toRemove = new HashMap<Task, Boolean>();
      for (Task task : tasks) {
        toRemove.put(task, true);
      }

      while (rs.next())
      {
        query = rs.getString(1);
        for (Task task : tasks) {
          if (querySimilarToTask(query, task)) {
            toRemove.put(task, false);
          }
        }
      }
      rs.close();
      st.close();

      for (Task task : toRemove.keySet()) {
        if (toRemove.get(task)) {
          tasks.remove(task);
        }
      }
    } catch (SQLException e) {
      System.err.println(e);
    }
  }

  private boolean querySimilarToTask(String query, Task task) {
    String lemmatizedTaskVerb = task.lemmatizedVerb;
    Set<String> nouns = task.nouns;

    Tree queryTree = parse(query);
    List<Tree> preorder = queryTree.preOrderNodeList();
    Set<String> lemmatizedQueryWords = new HashSet<String>();

    for (Tree t : preorder) {
      if (t.isLeaf()) {
        Tree parent = t.parent(queryTree);
        String word = t.label().value().toString();

        String parentLabel = parent.label().value().toString();
        lemmatizedQueryWords.add(Morphology.lemmaStatic(word, parentLabel, false));
      }
    }

    if (!lemmatizedQueryWords.contains(lemmatizedTaskVerb)) {
      return false;
    }

    for (String n : nouns) {
      if (lemmatizedQueryWords.contains(n)) {
        System.out.println("--- Found a verb and noun match!");
        return true;
      }
    }

    return false;
  }

  public Tree parse(String str) {
    List<CoreLabel> tokens = tokenize(str);
    Tree tree = parser.apply(tokens);
    return tree;
  }

  private List<CoreLabel> tokenize(String str) {
    Tokenizer<CoreLabel> tokenizer = tokenizerFactory.getTokenizer(new StringReader(str));
    return tokenizer.tokenize();
  }

  private class Task {
    public String taskString;
    public String lemmatizedTaskString;
    public String lemmatizedVerb;
    public Set<String> nouns;

    public Task(String taskString, String lemmatizedTaskString, String lemmatizedVerb, Set<String> nouns) {
      this.taskString = taskString;
      this.lemmatizedTaskString = lemmatizedTaskString;
      this.lemmatizedVerb = lemmatizedVerb;
      this.nouns = nouns;
    }
  }
}

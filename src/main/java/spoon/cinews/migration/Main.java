package spoon.cinews.migration;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;

import groovy.lang.Writable;
import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;
import net.htmlparser.jericho.Attribute;
import net.htmlparser.jericho.Attributes;
import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.OutputDocument;
import net.htmlparser.jericho.Source;
import net.htmlparser.jericho.StartTag;
import net.htmlparser.jericho.Util;

/**
 * @author Kureem Rossaye
 */
public class Main {

	private static Properties configs = new Properties();

	private static Properties mappings = new Properties();

	private static Template templateItem = null;

	private static Template templateRSS = null;

	private static int maxPostId = 3000;

	private static SimpleTemplateEngine engine = null;

	static {
		try {
			init();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private static void init() throws Exception {
		initConfigs();
		initEngine();
	}

	private static void initConfigs() throws Exception {
		configs.load(new FileInputStream(new File("configs/configs.properties")));
		mappings.load(new FileInputStream(new File("configs/mappings.properties")));
		maxPostId = Integer.parseInt(configs.getProperty("maxPostId", "3000"));
	}

	private static void initEngine() throws Exception {
		SimpleTemplateEngine engine = new SimpleTemplateEngine();
		templateItem = engine.createTemplate(new File("templates/item.xhtml"));
		templateRSS = engine.createTemplate(new File("templates/rss.xhtml"));
	}

	private Main addTag(String tag, List<Category> categories) {
		Category c = new Category();
		c.setDomain("post_tag");
		c.setNiceName(toslug(tag));
		c.setText(tag);
		categories.add(c);
		return this;
	}

	private Main addCategory(String category, List<Category> categories) {
		Category c = new Category();
		c.setDomain("category");
		c.setNiceName(toslug(category));
		c.setText(category);
		categories.add(c);
		return this;
	}

	private void start() throws Exception {
		int count = Integer.parseInt(configs.getProperty("maxItemsPerFile", "100"));
		List<String> items = new ArrayList<String>();
		int batch = 0;
		for (File f : getFiles()) {
			items.addAll(compile(f));
			if (items.size() >= count) {
				flush(items, "batch-" + batch + ".xml");
				batch++;
			}
		}
		flush(items, "batch-" + batch + ".xml");
		batch++;
	}

	private void flush(List<String> items, String fileName) throws Exception {

		Map<Object, Object> context = new HashMap<Object, Object>();

		context.put("lines", items);

		context.putAll(configs);

		String result = templateRSS.make(context).toString();
		result = result.replace("%5f", "%255f");

		Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("result/" + fileName), "UTF-8"));
		try {
			out.write(result);
		} finally {
			out.close();
		}

		items.clear();
	}

	private List<String> compile(File f) throws Exception {

		BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));

		Source s = new Source(in);

		List<String> result = new ArrayList<String>();

		List<Category> categories = new ArrayList<Category>();

		Map<Object, Object> context = new HashMap<Object, Object>();

		context.put("categories", categories);

		for (Element item : s.getAllElements("item")) {
			itemContext(item, context);
			Writable w = templateItem.make(context);
			result.add(w.toString());
		}

		return result;
	}

	private static String cleanUp(String s) {
		if (s.contains("["))
			return s.substring(0, s.indexOf('[') - 1);
		return s;
	}

	private String getTraction(Element item) {
		Element l = item.getFirstElement("guid");
		String link = l.getTextExtractor().toString();
		String[] ss = StringUtils.splitByWholeSeparator(link, "/");
		String last = ss[ss.length - 1];
		String cat = "";
		for (char c : last.toCharArray()) {
			try {
				Integer.parseInt(c + "");
				break;
			} catch (Exception ee) {
				cat = cat + c + "";
			}
		}
		return cat.toLowerCase().replace(" ", "");
	}

	public void printEnclosure() throws Exception {

		for (File f : getFiles()) {
			for (Element e : new Source(f).getAllElements("enclosure")) {
				System.out.println(e.getAttributeValue("url"));
			}
		}
		
	}

	public void downloadFiles() throws Exception {
		
		for (File f : getFiles()) {
			System.out.println("Analyzing:" + f.getName());
			Crawler.extractLink(new Source(f));
		}
		
	}

	private Main itemContext(Element item, Map<Object, Object> context) throws Exception {

		List<Category> terms = new ArrayList<Category>();
		context.putAll(configs);

		if (item.getFirstElement("title") == null) {
			return this;
		}

		String title = cleanUp(item.getFirstElement("title").getTextExtractor().toString());
		String pubDate = item.getFirstElement("dc:date").getTextExtractor().toString();
		String content = item.getFirstElement("content:encoded").toString().replace("<content:encoded>", "")
				.replace("</content:encoded>", "");

		content = rewriteUrl(content);

		String description = cleanUp(item.getFirstElement("description").getTextExtractor().toString());
		String category = getTraction(item);

		System.out.println(category);

		if (category != null)
			category = mappings.getProperty(category, category).toString();

		addCategory(category, terms);

		String congress = getCongress(item);
		if (congress != null) {
			String tag = mappings.getProperty(congress.toLowerCase());
			if (tag != null && tag.length() > 0) {
				addTag(tag, terms);
			}
		}

		context.put("title", title);
		context.put("pubDate", pubDate);
		context.put("content", content);
		context.put("excerpt", description);
		context.put("postId", (maxPostId++) + "");
		context.put("categories", terms);
		return this;
	}

	private String rewriteUrl(String content) {
		content = content.replace("<![CDATA[", "").replace(">]]", "");
		Source s = new Source(content);
		OutputDocument outputDocument = new OutputDocument(new Source(content));
		for (Element element : s.getAllElements()) {

			String tag = element.getStartTag().getName();

			if (tag.equalsIgnoreCase("img") || tag.equalsIgnoreCase("a")) {
				String attrName = "src";
				if (tag.equalsIgnoreCase("a")) {
					attrName = "href";
				}
				StartTag startTag = element.getStartTag();
				Attributes attributes = startTag.getAttributes();
				Attribute src = attributes.get(attrName);

				StringBuilder builder = new StringBuilder();

				if (src == null || src.getValue() == null) {
					continue;
				}
				if (src.getValue().startsWith("http://ci.beaufour-ipsen.com/db/attachments/")) {

					String url = src.getValue();
					url = url.replace("http://ci.beaufour-ipsen.com/", "").replace("/", "_");
					url = "http://wave-eu-west-1.s3-eu-west-1.amazonaws.com/websites/CINEWSBACK-PROD/wp-content/uploads/2016/02/"
							+ url;
					url = url.replace("?user-agent=rss", "");
					url = url.replace("db_attachments", "attachments");

					builder.append("<" + tag).append(" ").append(attrName + "=\"").append(url).append("\"");
					builder.append(">");
					outputDocument.replace(startTag, builder);
				}
			} else if (tag.equalsIgnoreCase("font")) {
				outputDocument.remove(element);
			} else if (element.getAttributeValue("class") != null
					&& element.getAttributeValue("class").equalsIgnoreCase("showallcomments")) {
				outputDocument.remove(element);
			}

		}
		return outputDocument.toString().replace("<br><br><br>", "").trim();
	}

	private static String swapLinks(String input) {
		String cleaned = input.replace("<![CDATA[", "").replace("]]", "");
		Source s = new Source(cleaned);
		s.fullSequentialParse();
		for (Element e : s.getAllElements("a")) {
			String href = e.getAttributeValue("href");
			href = href.replace("http://ci.beaufour-ipsen.com",
					"http://wave-eu-west-1.s3-eu-west-1.amazonaws.com/websites/CINEWSBACK-dev");
			for (Attribute a : e.getAttributes()) {
				if (a.getName().equals("href")) {
					System.out.println(a.getName());
				}
			}
		}
		return s.getTextExtractor().toString();
	}

	private static String toslug(String s) {
		return s.toLowerCase().replace("&", "-").replace(" ", "-");
	}

	private static String getCongress(Element e) {
		for (Element c : e.getAllElements("category")) {

			String category = c.getTextExtractor().toString();
			if (category.startsWith("congress:")) {
				String congress = category.replace("congress:", "");
				return congress;
			}
		}
		return null;
	}

	public void addFeaturedImage(String image) {

	}

	public void uploadImage(String image) {

	}

	public static void main(String[] args) throws Exception {
		new Main().start();
	}

	public File[] getFiles() {
		return new File(configs.getProperty("repository", "repository")).listFiles();
	}

	private List<String> extractCategories() throws Exception {

		List<String> result = new LinkedList<String>();

		for (File f : getFiles()) {
			Source s = new Source(f);

			for (Element e : s.getAllElements("item")) {
				Element l = e.getFirstElement("guid");
				String link = l.getTextExtractor().toString();
				String[] ss = StringUtils.splitByWholeSeparator(link, "/");
				String last = ss[ss.length - 1];
				String cat = "";
				for (char c : last.toCharArray()) {
					try {
						Integer.parseInt(c + "");
						break;
					} catch (Exception ee) {
						cat = cat + c + "";
					}
				}

				if (!result.contains(cat)) {
					result.add(cat);
				}

				for (Element c : e.getAllElements("category")) {
					String category = c.getTextExtractor().toString();
					if (category.startsWith("congress:")) {
						String congress = category.replace("congress:", "");
						if (!result.contains(congress)) {
							result.add(congress);
						}
					}
				}
			}
		}
		return result;
	}

	public void transformFiles(String rootFolder) throws Exception {
		File root = new File(rootFolder);
		for (File f : root.listFiles()) {
			if (f.isDirectory()) {
				transformFiles(f.getAbsolutePath());
			} else {
				changeName(f);
			}
		}
	}

	private static String mapName(String s) {
		return s.replace("\\", "_").replace("C:_", "");
	}

	private void changeName(File f) throws Exception {

		String output = "C:\\Users\\Kurreem\\renamed\\" + mapName(f.getAbsolutePath());
		System.out.println(output);
		FileUtils.copyFile(f, new File(output));
	}

	public static void main_(String[] args) throws Exception {
		String sourceUrlString = "data/form.html";
		if (args.length == 0)
			System.err.println("Using default argument of \"" + sourceUrlString + '"');
		else
			sourceUrlString = args[0];
		if (sourceUrlString.indexOf(':') == -1)
			sourceUrlString = "file:" + sourceUrlString;
		URL sourceUrl = new URL(sourceUrlString);
		Source source = new Source(sourceUrl);
		OutputDocument outputDocument = new OutputDocument(source);
		StringBuilder sb = new StringBuilder();
		List<StartTag> linkStartTags = source.getAllStartTags(HTMLElementName.LINK);
		for (StartTag startTag : linkStartTags) {
			Attributes attributes = startTag.getAttributes();
			String rel = attributes.getValue("rel");
			if (!"stylesheet".equalsIgnoreCase(rel))
				continue;
			String href = attributes.getValue("href");

			if (href == null)
				continue;
			String styleSheetContent;
			try {
				styleSheetContent = Util.getString(new InputStreamReader(new URL(sourceUrl, href).openStream()));
			} catch (Exception ex) {
				System.err.println(ex.toString());
				continue;
			}
			sb.setLength(0);
			sb.append("<style");
			Attribute typeAttribute = attributes.get("type");
			if (typeAttribute != null)
				sb.append(' ').append(typeAttribute);
			sb.append(">\n").append(styleSheetContent).append("\n</style>");
			outputDocument.replace(startTag, sb.toString());
		}
		System.err.println("Here is the document " + sourceUrlString
				+ " with all external stylesheets converted to inline stylesheets:\n");
		outputDocument.writeTo(new OutputStreamWriter(System.out));
	}
}

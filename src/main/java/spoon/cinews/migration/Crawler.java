package spoon.cinews.migration;

import java.io.File;
import java.net.URL;

import org.apache.commons.io.FileUtils;

import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.Source;

public class Crawler {
	
	public static void extractLink(Source s)throws Exception{
		for(Element e : s.getAllElements("enclosure")){
			String link = e.getAttributeValue("url");
			
			downloadLink(link);
		}
	}
	
	public static void downloadLink(String link)throws Exception{
		String path = link.replace("http://ci.beaufour-ipsen.com/", "");
		File f = new File(path).getParentFile();
		f.mkdirs();
		System.out.println("Downloading " + link + " to " + f.getAbsolutePath());
		FileUtils.copyURLToFile(new URL(link),new File(path));
	}
	
	public static void archive(String link, File f){
		
	}
	
	
	public void swapLink(){
		
	}

	public static void main(String[] args) {

	}

}

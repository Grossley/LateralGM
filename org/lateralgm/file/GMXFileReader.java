/**
* @file  GMXFileReader.java
* @brief Class implementing a GMX file reader.
*
* @section License
*
* Copyright (C) 2013-2015,2019,2021 Robert B. Colton
* This file is a part of the LateralGM IDE.
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
**/

package org.lateralgm.file;

import static org.lateralgm.file.ProjectFile.interfaceProvider;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Stack;

import javax.imageio.ImageIO;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.lateralgm.components.impl.ResNode;
import org.lateralgm.file.ProjectFile.InterfaceProvider;
import org.lateralgm.file.iconio.ICOFile;
import org.lateralgm.main.Util;
import org.lateralgm.resources.Background;
import org.lateralgm.resources.Background.PBackground;
import org.lateralgm.resources.Constants;
import org.lateralgm.resources.Font;
import org.lateralgm.resources.Font.PFont;
import org.lateralgm.resources.GameInformation;
import org.lateralgm.resources.GameInformation.PGameInformation;
import org.lateralgm.resources.GameSettings;
import org.lateralgm.resources.GameSettings.PGameSettings;
import org.lateralgm.resources.GmObject;
import org.lateralgm.resources.GmObject.PGmObject;
import org.lateralgm.resources.Path;
import org.lateralgm.resources.Path.PPath;
import org.lateralgm.resources.Resource;
import org.lateralgm.resources.Room;
import org.lateralgm.resources.Room.PRoom;
import org.lateralgm.resources.Script;
import org.lateralgm.resources.Script.PScript;
import org.lateralgm.resources.Shader;
import org.lateralgm.resources.Shader.PShader;
import org.lateralgm.resources.Sound;
import org.lateralgm.resources.Sound.PSound;
import org.lateralgm.resources.Sprite;
import org.lateralgm.resources.Sprite.PSprite;
import org.lateralgm.resources.Timeline;
import org.lateralgm.resources.library.LibAction;
import org.lateralgm.resources.library.LibArgument;
import org.lateralgm.resources.library.LibManager;
import org.lateralgm.resources.sub.Action;
import org.lateralgm.resources.sub.ActionContainer;
import org.lateralgm.resources.sub.Argument;
import org.lateralgm.resources.sub.BackgroundDef;
import org.lateralgm.resources.sub.BackgroundDef.PBackgroundDef;
import org.lateralgm.resources.sub.Event;
import org.lateralgm.resources.sub.GlyphMetric;
import org.lateralgm.resources.sub.GlyphMetric.PGlyphMetric;
import org.lateralgm.resources.sub.Instance;
import org.lateralgm.resources.sub.Instance.PInstance;
import org.lateralgm.resources.sub.MainEvent;
import org.lateralgm.resources.sub.Moment;
import org.lateralgm.resources.sub.PathPoint;
import org.lateralgm.resources.sub.ShapePoint;
import org.lateralgm.resources.sub.Tile;
import org.lateralgm.resources.sub.Tile.PTile;
import org.lateralgm.resources.sub.View;
import org.lateralgm.resources.sub.View.PView;
import org.lateralgm.util.PropertyMap;

public final class GMXFileReader
	{
	public static final String STUPID_SHADER_MARKER =
			"//######################_==_YOYO_SHADER_MARKER_==_######################@~"; //$NON-NLS-1$

	private static XMLInputFactory xmlInputFactory;
	private static Queue<PostponedRef> postpone = new LinkedList<PostponedRef>();

	static interface PostponedRef
		{
		boolean invoke();
		}

	static class DefaultPostponedRef<K extends Enum<K>> implements PostponedRef
		{
		ResourceList<?> list;
		String name;
		PropertyMap<K> p;
		K key;

		DefaultPostponedRef(ResourceList<?> list, PropertyMap<K> p, K key, String name)
			{
			this.list = list;
			this.p = p;
			this.key = key;
			this.name = name;
			}

		public boolean invoke()
			{
			Resource<?,?> temp = list.get(name);
			if (temp != null) p.put(key,temp.reference);
			return temp != null;
			}
		}

	private GMXFileReader()
		{
		}

	private static XMLEventReader parseDocumentUnchecked2(ProjectFile f, String path) throws GmFormatException
		{
		XMLEventReader reader = null;
		try
			{
			reader = xmlInputFactory.createXMLEventReader(new FileInputStream(path));
			}
		catch (FileNotFoundException e)
			{
			throw new GmFormatException(f, "file not found: " + path, e);
			}
		catch (XMLStreamException e)
			{
			throw new GmFormatException(f, "failed to parse: " + path, e);
			}
		return reader;
		}

	private static XMLEventReader parseDocumentChecked2(ProjectFile f, String path)
		{
		XMLEventReader reader = null;
		try
			{
			reader = parseDocumentUnchecked2(f, path);
			}
		catch (GmFormatException e)
			{
			interfaceProvider.handleException(e);
			}
		return reader;
		}

	//Workaround for Parameter limit
	private static class ProjectFileContext
		{
		ProjectFile f;
		XMLEventReader in;
		RefList<Timeline> timeids;
		RefList<GmObject> objids;
		RefList<Room> rmids;

		public ProjectFileContext(ProjectFile f, XMLEventReader in, RefList<Timeline> timeids,
				RefList<GmObject> objids, RefList<Room> rmids)
			{
			this.f = f;
			this.in = in;
			this.timeids = timeids;
			this.objids = objids;
			this.rmids = rmids;
			}

		public ProjectFileContext copy()
			{
			return new ProjectFileContext(f,in,timeids,objids,rmids);
			}
		}

	private static GmFormatException versionError(ProjectFile f, String error, String res, int ver)
		{
		return versionError(f,error,res,0,ver);
		}

	private static GmFormatException versionError(ProjectFile f, String error, String res, int i,
			int ver)
		{
		InterfaceProvider ip = interfaceProvider;
		return new GmFormatException(f,ip.format(
				"ProjectFileReader.ERROR_UNSUPPORTED",ip.format( //$NON-NLS-1$
						"ProjectFileReader." + error,ip.translate("LGM." + res),i),ver)); //$NON-NLS-1$  //$NON-NLS-2$
		}

	public static void readProjectFile(InputStream stream, ProjectFile file, URI uri, ResNode root)
			throws GmFormatException
		{
		readProjectFile(stream,file,uri,root,null);
		}

	public static void readProjectFile(InputStream stream, ProjectFile file, URI uri, ResNode root,
			Charset forceCharset) throws GmFormatException
		{
		interfaceProvider.init(160,"ProgressDialog.GMX_LOADING"); //$NON-NLS-1$
		file.format = ProjectFile.FormatFlavor.GMX;
		if (xmlInputFactory == null)
			xmlInputFactory = XMLInputFactory.newInstance();
		RefList<Timeline> timeids = new RefList<Timeline>(Timeline.class); // timeline ids
		RefList<GmObject> objids = new RefList<GmObject>(GmObject.class); // object ids
		RefList<Room> rmids = new RefList<Room>(Room.class); // room id

		try
			{
			XMLEventReader document = GMXFileReader.parseDocumentUnchecked2(file, file.getPath());

			ProjectFileContext c = new ProjectFileContext(file,document,timeids,objids,rmids);

			// clear old/default configurations
			c.f.gameSettings.clear();

			ResNode snode = new ResNode("Game Settings",ResNode.STATUS_SECONDARY,GameSettings.class,null);
			root.add(snode);
			ResNode inode = new ResNode(Resource.kindNamesPlural.get(GameInformation.class),
					ResNode.STATUS_SECONDARY,GameInformation.class,c.f.gameInfo.reference);
			root.add(inode);

			Stack<ResNode> nodes = new Stack<>();
			while (document.hasNext())
				{
				XMLEvent nextEvent = null;
				try
					{
					nextEvent = document.nextEvent();
					}
				catch (XMLStreamException e)
					{
					// TODO Auto-generated catch block
					e.printStackTrace();
					}

				if (!nextEvent.isStartElement())
					{
					if (nextEvent.isEndElement() && !nodes.isEmpty())
						nodes.pop();
					continue;
					}
				StartElement sel = nextEvent.asStartElement();
				String scope = sel.getName().getLocalPart();

				ResNode node = nodes.isEmpty() ? root : nodes.peek();
				if (GMXFileWriter.tagNames.containsValue(scope)) //$NON-NLS-1$
					{
					String groupName = nodes.isEmpty() ?
							"wtf" :
							sel.getAttributeByName(new QName("name")).getValue(); //$NON-NLS-1$
					ResNode rnode = new ResNode(groupName,(node == null) ? ResNode.STATUS_PRIMARY : ResNode.STATUS_GROUP,Sprite.class,null);
					node.add(rnode);
					nodes.push(rnode);
					continue;
					}

				switch (scope)
					{
					case "sprite": readSprite(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "sound": readSound(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "background": readBackground(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "path": readPath(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "script": readScript(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "shader": readShader(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "font": readFont(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "timeline": readTimeline(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "object": readGmObject(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "room": readRoom(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "datafile": readInclude(c,node,document.getElementText()); break; //$NON-NLS-1$
					case "Config": readConfig(c,document.getElementText()); break; //$NON-NLS-1$
					case "rtf": readGameInformation(c,document.getElementText()); break; //$NON-NLS-1$
					}
				}

			interfaceProvider.setProgress(160,"ProgressDialog.POSTPONED"); //$NON-NLS-1$
			// All resources read, now we can invoke our postponed references.
			for (PostponedRef i : postpone)
				i.invoke();
			postpone.clear();

			interfaceProvider.setProgress(160,"ProgressDialog.FINISHED"); //$NON-NLS-1$
			}
		catch (Exception e)
			{
			if ((e instanceof GmFormatException)) throw (GmFormatException) e;
			throw new GmFormatException(file,e);
			}
		finally
			{
			try
				{
				if (stream != null)
					{
					stream.close();
					stream = null;
					}
				}
			catch (IOException ex)
				{
				String key = interfaceProvider.translate("GmFileReader.ERROR_CLOSEFAILED"); //$NON-NLS-1$
				throw new GmFormatException(file,key);
				}
			}
		}

	private static void readConfig(ProjectFileContext c, String cNode)
		{
		GameSettings gSet = new GameSettings();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		gSet.setName(fileName);

		c.f.gameSettings.add(gSet);
		PropertyMap<PGameSettings> pSet = gSet.properties;

		String path = c.f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(c.f, path + ".config.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				data = reader.getElementText();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}
			if (data.isEmpty()) continue;
			
			switch (scope)
				{
				case "option_sync_vertex": //$NON-NLS-1$
					{
					// For some odd reason these two settings are combined together.
					// 2147483649 - Both
					// 2147483648 - Software Vertex Processing only
					// 1 - Synchronization Only
					// 0 - None
					long syncvertex = Long.parseLong(data);
					gSet.put(PGameSettings.USE_SYNCHRONIZATION,(syncvertex == 2147483649L || syncvertex == 1));
					pSet.put(PGameSettings.FORCE_SOFTWARE_VERTEX_PROCESSING,
							(syncvertex == 2147483649L || syncvertex == 2147483648L));
					break;
					}
				case "option_use_new_audio": gSet.put(PGameSettings.USE_NEW_AUDIO,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_shortcircuit": gSet.put(PGameSettings.SHORT_CIRCUIT_EVAL,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_use_fast_collision": //$NON-NLS-1$
					gSet.put(PGameSettings.USE_FAST_COLLISION,Boolean.parseBoolean(data)); break;
				case "option_fast_collision_compatibility": //$NON-NLS-1$
					gSet.put(PGameSettings.FAST_COLLISION_COMPAT,Boolean.parseBoolean(data)); break;
				case "option_fullscreen": gSet.put(PGameSettings.START_FULLSCREEN,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_sizeable": gSet.put(PGameSettings.ALLOW_WINDOW_RESIZE,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_stayontop": gSet.put(PGameSettings.ALWAYS_ON_TOP,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_aborterrors": gSet.put(PGameSettings.ABORT_ON_ERROR,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				// TODO: This value is stored using the Windows native dialog's name for the color value, ie
				// "clBlack" or "clWhite" meaning black and white respectively. If the user chooses a custom
				// defined color in the dialog, then the value is in the hexadecimal form "$HHHHHHHH" using
				// a dollar sign instead of a hash sign as a normal hex color value does in other places in
				// the same configuration file.
				case "option_windowcolor": /*gSet.put(PGameSettings.COLOR_OUTSIDE_ROOM,data);*/ break; //$NON-NLS-1$
				case "option_noscreensaver": gSet.put(PGameSettings.DISABLE_SCREENSAVERS,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_showcursor": gSet.put(PGameSettings.DISPLAY_CURSOR,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_displayerrors": gSet.put(PGameSettings.DISPLAY_ERRORS,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_noborder": gSet.put(PGameSettings.DONT_DRAW_BORDER,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_nobuttons": gSet.put(PGameSettings.DONT_SHOW_BUTTONS,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_argumenterrors": gSet.put(PGameSettings.ERROR_ON_ARGS,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_freeze": gSet.put(PGameSettings.FREEZE_ON_LOSE_FOCUS,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_colordepth": //$NON-NLS-1$
					gSet.put(PGameSettings.COLOR_DEPTH, ProjectFile.GS_DEPTHS[Integer.parseInt(data)]); break;
				case "option_frequency": //$NON-NLS-1$
					gSet.put(PGameSettings.FREQUENCY, ProjectFile.GS_FREQS[Integer.parseInt(data)]); break;
				case "option_resolution": //$NON-NLS-1$
					gSet.put(PGameSettings.RESOLUTION, ProjectFile.GS_RESOLS[Integer.parseInt(data)]); break;
				case "option_changeresolution": gSet.put(PGameSettings.SET_RESOLUTION, Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_priority": //$NON-NLS-1$
					gSet.put(PGameSettings.GAME_PRIORITY, ProjectFile.GS_PRIORITIES[Integer.parseInt(data)]); break;
				case "option_closeesc": //$NON-NLS-1$
					gSet.put(PGameSettings.LET_ESC_END_GAME,Boolean.parseBoolean(data));
					gSet.put(PGameSettings.TREAT_CLOSE_AS_ESCAPE,Boolean.parseBoolean(data));
					break;
				case "option_interpolate": gSet.put(PGameSettings.INTERPOLATE,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_scale": gSet.put(PGameSettings.SCALING,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_lastchanged": gSet.put(PGameSettings.LAST_CHANGED,Double.parseDouble(data)); break; //$NON-NLS-1$
				case "option_gameid": gSet.put(PGameSettings.GAME_ID,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_gameguid": gSet.setGUID(data); break; //$NON-NLS-1$
				case "option_author": gSet.put(PGameSettings.AUTHOR,data); break; //$NON-NLS-1$
				case "option_version_company": gSet.put(PGameSettings.COMPANY,data); break; //$NON-NLS-1$
				case "option_version_copyright": gSet.put(PGameSettings.COPYRIGHT,data); break; //$NON-NLS-1$
				case "option_version_description": gSet.put(PGameSettings.DESCRIPTION,data); break; //$NON-NLS-1$
				case "option_version_product": gSet.put(PGameSettings.PRODUCT,data); break; //$NON-NLS-1$
				case "option_information": gSet.put(PGameSettings.INFORMATION,data); break; //$NON-NLS-1$
				case "option_version": gSet.put(PGameSettings.VERSION,data); break; //$NON-NLS-1$
				case "option_version_build": gSet.put(PGameSettings.VERSION_BUILD,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_version_major": gSet.put(PGameSettings.VERSION_MAJOR,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_version_minor": gSet.put(PGameSettings.VERSION_MINOR,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_version_release": gSet.put(PGameSettings.VERSION_RELEASE,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_windows_steam_app_id": gSet.put(PGameSettings.WINDOWS_STEAM_ID,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_mac_steam_app_id": gSet.put(PGameSettings.MAC_STEAM_ID,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_linux_steam_app_id": gSet.put(PGameSettings.LINUX_STEAM_ID,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "option_windows_enable_steam": //$NON-NLS-1$
					gSet.put(PGameSettings.WINDOWS_STEAM_ENABLE,Boolean.parseBoolean(data)); break;
				case "option_mac_enable_steam": gSet.put(PGameSettings.MAC_STEAM_ENABLE,Boolean.parseBoolean(data)); break; //$NON-NLS-1$
				case "option_linux_enable_steam": //$NON-NLS-1$
					gSet.put(PGameSettings.LINUX_STEAM_ENABLE,Boolean.parseBoolean(data)); break;
				case "option_windows_game_icon": //$NON-NLS-1$
					{
					String icopath = c.f.getDirectory() + '/' + data;
					icopath = Util.getPOSIXPath(icopath);
					try
						{
						// the icon not existing is a silent fail in GMSv1.4
						// which will automatically recreate it as the default
						// probably because the IDE does not require it, but
						// the asset compiler does
						if (new File(icopath).exists()) // << GMZ does not export if default icon
							pSet.put(PGameSettings.GAME_ICON,new ICOFile(icopath));
						}
					catch (IOException e)
						{
						interfaceProvider.handleException(new GmFormatException(c.f, "failed to read: " + icopath, e));
						}
					break;
					}
				}
			}
		}

	private static void readSprite(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		Sprite spr = f.resMap.getList(Sprite.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		spr.setName(fileName);
		ResNode rnode = new ResNode(spr.getName(),ResNode.STATUS_SECONDARY,Sprite.class,spr.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		spr.put(PSprite.TRANSPARENT,false);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".sprite.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (nextEvent.isCharacters())
					data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "colkind": //$NON-NLS-1$
					spr.put(PSprite.SHAPE,ProjectFile.SPRITE_MASK_SHAPE[Integer.parseInt(data)]); break;
				case "sepmasks": spr.put(PSprite.SEPARATE_MASK,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "bboxmode": //$NON-NLS-1$
					spr.put(PSprite.BB_MODE,ProjectFile.SPRITE_BB_MODE[Integer.parseInt(data)]); break;
				case "bbox_left": spr.put(PSprite.BB_LEFT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "bbox_right": spr.put(PSprite.BB_RIGHT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "bbox_top": spr.put(PSprite.BB_TOP,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "bbox_bottom": spr.put(PSprite.BB_BOTTOM,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "coltolerance": spr.put(PSprite.ALPHA_TOLERANCE,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "xorig": spr.put(PSprite.ORIGIN_X,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "yorigin": spr.put(PSprite.ORIGIN_Y,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "HTile": spr.put(PSprite.TILE_HORIZONTALLY,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "VTile": spr.put(PSprite.TILE_VERTICALLY,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "For3D": spr.put(PSprite.FOR3D,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				// TODO: Read texture groups
				// NOTE: Just extra metadata stored in the GMX by studio
				case "width": case "height": break; //$NON-NLS-1$ //$NON-NLS-2$
				case "frames": //$NON-NLS-1$
					{
					path = f.getDirectory() + "/sprites/"; //$NON-NLS-1$

					while (reader.hasNext())
						{
						try
							{
							nextEvent = reader.nextEvent();
							}
						catch (XMLStreamException e)
							{
							// TODO Auto-generated catch block
							e.printStackTrace();
							break;
							}
						if (!nextEvent.isStartElement()) continue;
						sel = nextEvent.asStartElement();
						if (!sel.getName().getLocalPart().equals("frame")) continue;
						if (!reader.hasNext()) break;
						data = "";
						try
							{
							data = reader.nextEvent().asCharacters().getData();
							}
						catch (XMLStreamException e1)
							{
							// TODO Auto-generated catch block
							e1.printStackTrace();
							}
						BufferedImage img = null;
						File imgfile = new File(path + Util.getPOSIXPath(data));
						if (imgfile.exists())
							{
							try
								{
								img = ImageIO.read(imgfile);
								spr.subImages.add(img);
								}
							catch (IOException e)
								{
								interfaceProvider.handleException(new GmFormatException(c.f, "failed to read: " + imgfile.getAbsolutePath(), e));
								}
							}
						}
					break;
					}
				}
			}
		}

	private static void readSound(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		Sound snd = f.resMap.getList(Sound.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		snd.setName(fileName);
		ResNode rnode = new ResNode(snd.getName(),ResNode.STATUS_SECONDARY,Sound.class,snd.reference);
		node.add(rnode);
		snd.setNode(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".sound.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (nextEvent.asCharacters().isWhiteSpace()) continue;
				data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}
			if (data == null || data.isEmpty()) continue;

			switch (scope)
				{
				case "origname": snd.put(PSound.FILE_NAME,data); break; //$NON-NLS-1$
				case "extension": snd.put(PSound.FILE_TYPE,data); break; //$NON-NLS-1$
				// GMX uses double nested tags for volume, bit rate, sample rate, type, and bit depth
				// There is a special clause here, every one of those tags after volume, the nested
				// tag is singular, where its parent is plural.
				case "volume": snd.put(PSound.VOLUME,Double.parseDouble(data)); break;
				case "pan": snd.put(PSound.PAN,Double.parseDouble(data)); break; //$NON-NLS-1$
				case "bitRate":  snd.put(PSound.BIT_RATE,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "sampleRate": snd.put(PSound.SAMPLE_RATE,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "type": snd.put(PSound.TYPE,ProjectFile.SOUND_TYPE[Integer.parseInt(data)]); break; //$NON-NLS-1$
				case "bitDepth": snd.put(PSound.BIT_DEPTH,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "preload": snd.put(PSound.PRELOAD,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "compressed": snd.put(PSound.COMPRESSED,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "streamed": snd.put(PSound.STREAMED,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "uncompressOnLoad": snd.put(PSound.DECOMPRESS_ON_LOAD,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "kind": snd.put(PSound.KIND,ProjectFile.SOUND_KIND[Integer.parseInt(data)]); break; //$NON-NLS-1$
				case "effects": snd.setEffects(Integer.parseInt(data)); break; //$NON-NLS-1$
				case "data": //$NON-NLS-1$
					String fname = f.getDirectory() + "/sound/audio/" + data;
					try
						{
						snd.data = Util.readFully(fname);
						}
					catch (IOException e)
						{
						interfaceProvider.handleException(new GmFormatException(c.f, "failed to read: " + fname, e));
						}
					break;
				}
			}
		}

	private static void readBackground(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		Background bkg = f.resMap.getList(Background.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		bkg.setName(fileName);
		ResNode rnode = new ResNode(bkg.getName(),ResNode.STATUS_SECONDARY,Background.class,bkg.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".background.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				data = reader.nextEvent().asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "istileset": bkg.put(PBackground.USE_AS_TILESET,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "tilewidth": bkg.put(PBackground.TILE_WIDTH,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "tileheight": bkg.put(PBackground.TILE_HEIGHT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "tilexoff": bkg.put(PBackground.H_OFFSET,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "tileyoff": bkg.put(PBackground.V_OFFSET,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "tilehsep": bkg.put(PBackground.H_SEP,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "tilevsep": bkg.put(PBackground.V_SEP,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "HTile": bkg.put(PBackground.TILE_HORIZONTALLY,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "VTile": bkg.put(PBackground.TILE_VERTICALLY,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "For3D": bkg.put(PBackground.FOR3D,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				// TODO: Read texture groups
				// NOTE: Just extra metadata stored in the GMX by studio
				case "width": case "height": break; //$NON-NLS-1$ //$NON-NLS-2$
				case "data": //$NON-NLS-1$
					path = f.getDirectory() + "/background/"; //$NON-NLS-1$
					BufferedImage img = null;
					File imgfile = new File(path + Util.getPOSIXPath(data));
					if (imgfile.exists())
						{
						try
							{
							img = ImageIO.read(imgfile);
							bkg.setBackgroundImage(img);
							}
						catch (IOException e)
							{
							interfaceProvider.handleException(
									new GmFormatException(c.f, "failed to read: " + imgfile.getAbsolutePath(), e));
							}
						}
					break;
				}
			}
		}

	private static void readPath(ProjectFileContext c, ResNode node, String cNode)
		{
		final ProjectFile f = c.f;

		final Path pth = f.resMap.getList(Path.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		pth.setName(fileName);
		ResNode rnode = new ResNode(pth.getName(),ResNode.STATUS_SECONDARY,Path.class,pth.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".path.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				data = reader.nextEvent().asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "kind": pth.put(PPath.SMOOTH,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "precision": pth.put(PPath.PRECISION,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "closed": pth.put(PPath.CLOSED,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "backroom": //$NON-NLS-1$
					final int backroom = Integer.parseInt(data);
					if (backroom < 0) break;
					PostponedRef pr = new PostponedRef()
						{
						public boolean invoke()
							{
							ResourceList<Room> list = f.resMap.getList(Room.class);
							if (list == null)
								{
								return false;
								}

							Room rmn = list.getUnsafe(backroom);
							if (rmn == null)
								{
								return false;
								}

							pth.put(PPath.BACKGROUND_ROOM,rmn.reference);
							return true;
							}
						};
						postpone.add(pr);
						break;
				case "hsnap": pth.put(PPath.SNAP_X,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "vsnap": pth.put(PPath.SNAP_Y,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "point": //$NON-NLS-1$
					String[] coords = data.split(","); //$NON-NLS-1$
					pth.points.add(new PathPoint(Integer.parseInt(coords[0]),Integer.parseInt(coords[1]),
						Integer.parseInt(coords[2])));
					break;
				}
			}
		}

	private static void readScript(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		Script scr = f.resMap.getList(Script.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		scr.setName(fileName.substring(0,fileName.lastIndexOf('.')));
		ResNode rnode = new ResNode(scr.getName(),ResNode.STATUS_SECONDARY,Script.class,scr.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		try (BufferedReader reader = new BufferedReader(new FileReader(path)))
			{
			String code = ""; //$NON-NLS-1$
			String line = reader.readLine();
			if (line == null) return;
			if (line.startsWith("#define")) //$NON-NLS-1$
				{
				line = reader.readLine();
				if (line == null) return;
				}
			do
				{
				if (line.startsWith("#define")) //$NON-NLS-1$
					{
					scr.put(PScript.CODE,code);
					code = ""; //$NON-NLS-1$

					scr = f.resMap.getList(Script.class).add();
					scr.setName(line.substring(8, line.length()));
					rnode = new ResNode(scr.getName(),ResNode.STATUS_SECONDARY,Script.class,scr.reference);
					node.add(rnode);
					}
				else
					code += line + '\n';
				}
			while ((line = reader.readLine()) != null);

			scr.put(PScript.CODE,code);
			}
		catch (FileNotFoundException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "file not found: " + path, e));
			}
		catch (IOException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "unable to read file: " + path, e));
			}
		}

	private static void readShader(ProjectFileContext c, ResNode node, String cNode)
		{
		if (true) return;
		ProjectFile f = c.f;

		Shader shr = f.resMap.getList(Shader.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		shr.setName(fileName.substring(0,fileName.lastIndexOf('.')));
		ResNode rnode = new ResNode(shr.getName(),ResNode.STATUS_SECONDARY,Shader.class,shr.reference);
		node.add(rnode);
		//shr.put(PShader.TYPE,cNode.getAttributes().item(0).getTextContent());
		String code = ""; //$NON-NLS-1$
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		try (BufferedReader reader = new BufferedReader(new FileReader(path)))
			{
			String line = ""; //$NON-NLS-1$
			while ((line = reader.readLine()) != null)
				{
				code += line + '\n';
				}
			}
		catch (FileNotFoundException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "file not found: " + path, e));
			}
		catch (IOException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "unable to read file: " + path, e));
			}

		String[] splitcode = code.split(STUPID_SHADER_MARKER);
		shr.put(PShader.VERTEX,splitcode[0]);
		shr.put(PShader.FRAGMENT,splitcode[1]);
		}

	private static void readFont(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		Font fnt = f.resMap.getList(Font.class).add();
		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		fnt.setName(fileName);
		ResNode rnode = new ResNode(fnt.getName(),ResNode.STATUS_SECONDARY,Font.class,fnt.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".font.gmx");
		if (reader == null) return;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (!nextEvent.isCharacters()) continue;
				data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "name": fnt.put(PFont.FONT_NAME,data); break; //$NON-NLS-1$
				case "size": fnt.put(PFont.SIZE,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "bold": fnt.put(PFont.BOLD,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "italic": fnt.put(PFont.ITALIC,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "charset": fnt.put(PFont.CHARSET,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "aa": fnt.put(PFont.ANTIALIAS,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "range0": //$NON-NLS-1$
					String[] range = data.split(","); //$NON-NLS-1$
					fnt.addRange(Integer.parseInt(range[0]),Integer.parseInt(range[1]));
					break;
				case "glyph": //$NON-NLS-1$
					GlyphMetric gm = fnt.addGlyph();
					PropertyMap<PGlyphMetric> props = gm.properties;
					Iterator<Attribute> atts = sel.getAttributes();
					while (atts.hasNext())
						{
						Attribute att = atts.next();
						switch (att.getName().getLocalPart())
							{
							case "character": props.put(PGlyphMetric.CHARACTER, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "x": props.put(PGlyphMetric.X, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "y": props.put(PGlyphMetric.Y, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "w": props.put(PGlyphMetric.W, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "h": props.put(PGlyphMetric.H, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "shift": props.put(PGlyphMetric.SHIFT, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "offset": props.put(PGlyphMetric.OFFSET, Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							}
						}
					break;
				}
			}
		}

	private static void readTimeline(ProjectFileContext c, ResNode node, String cNode)
		{
		ProjectFile f = c.f;

		//ResourceReference<Timeline> r = c.timeids.get(i); //includes ID
		//Timeline tml = r.get();
		//f.resMap.getList(Timeline.class).add(tml);

		Timeline tml = f.resMap.getList(Timeline.class).add();

		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		tml.setName(fileName);
		ResNode rnode = new ResNode(tml.getName(),ResNode.STATUS_SECONDARY,Timeline.class,tml.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".timeline.gmx");
		if (reader == null) return;

		int stepnum = 0;
		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (!nextEvent.isCharacters()) continue;
				data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "step": stepnum = Integer.parseInt(data); break; //$NON-NLS-1$
				case "event": //$NON-NLS-1$
					Moment mom = tml.addMoment();
					mom.stepNo = stepnum;
					readActions(c,mom,"INTIMELINEACTION",tml.getId(),mom.stepNo,reader); //$NON-NLS-1$
					break;
				}
			}
		}

	private static void readGmObject(ProjectFileContext c, ResNode node, String cNode)
		{
		final ProjectFile f = c.f;

		//ResourceReference<GmObject> r = c.objids.get(int); //includes ID
		//final GmObject obj = r.get();
		//f.resMap.getList(GmObject.class).add(obj);

		final GmObject obj = f.resMap.getList(GmObject.class).add();

		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		obj.setName(fileName);

		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		ResNode rnode = new ResNode(obj.getName(),ResNode.STATUS_SECONDARY,GmObject.class,obj.reference);
		node.add(rnode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".object.gmx");
		if (reader == null) return;

		int stepnum = 0;
		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement()) continue;
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (!nextEvent.isCharacters()) continue;
				data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "spriteName": //$NON-NLS-1$
					if (!data.equals("<undefined>")) //$NON-NLS-1$
						postpone.add(new DefaultPostponedRef<>(f.resMap.getList(Sprite.class), obj.properties, PGmObject.SPRITE, data));
					break;
				case "maskName": //$NON-NLS-1$
					if (!data.equals("<undefined>")) //$NON-NLS-1$
						postpone.add(new DefaultPostponedRef<>(f.resMap.getList(Sprite.class), obj.properties, PGmObject.MASK, data));
					break;
				case "parentName": //$NON-NLS-1$
					if (!data.equals("<undefined>") && !data.equals("self")) //$NON-NLS-1$ //$NON-NLS-2$
						postpone.add(new DefaultPostponedRef<>(f.resMap.getList(GmObject.class), obj.properties, PGmObject.PARENT, data));
					break;
				case "solid": obj.put(PGmObject.SOLID,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "visible": obj.put(PGmObject.VISIBLE,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "depth": obj.put(PGmObject.DEPTH,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "persistent": obj.put(PGmObject.PERSISTENT,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "event": //$NON-NLS-1$
					{
					final Event ev = new Event();

					ev.mainId = Integer.parseInt(sel.getAttributeByName(new QName("eventtype")).getValue()); //$NON-NLS-1$
					MainEvent me = obj.mainEvents.get(ev.mainId);
					me.events.add(0,ev);
					if (ev.mainId == MainEvent.EV_COLLISION)
						{
						final String colname = sel.getAttributeByName(new QName("ename")).getValue(); //$NON-NLS-1$
						PostponedRef pr = new PostponedRef()
							{
								public boolean invoke()
									{
									ResourceList<GmObject> list = f.resMap.getList(GmObject.class);
									if (list == null)
										{
										return false;
										}
									GmObject col = list.get(colname);
									if (col == null)
										{
										return false;
										}
									ev.other = col.reference;
									return true;
									}
							};
						postpone.add(pr);
						}
					else
						{
						ev.id = Integer.parseInt(sel.getAttributeByName(new QName("enumb")).getValue()); //$NON-NLS-1$
						}
					readActions(c,ev,"INOBJECTACTION",obj.getId(),ev.mainId * 1000 + ev.id,reader); //$NON-NLS-1$
					}
					break;
				case "PhysicsObject": obj.put(PGmObject.PHYSICS_OBJECT, Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "PhysicsObjectSensor": obj.put(PGmObject.PHYSICS_SENSOR, Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "PhysicsObjectShape": obj.put(PGmObject.PHYSICS_SHAPE, ProjectFile.PHYSICS_SHAPE[Integer.parseInt(data)]); break; //$NON-NLS-1$
				case "PhysicsObjectDensity": obj.put(PGmObject.PHYSICS_DENSITY, Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsObjectRestitution": obj.put(PGmObject.PHYSICS_RESTITUTION, Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsObjectGroup": obj.put(PGmObject.PHYSICS_GROUP, Integer.parseInt(data)); break; //$NON-NLS-1$
				case "PhysicsObjectLinearDamping": obj.put(PGmObject.PHYSICS_DAMPING_LINEAR, Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsObjectAngularDamping": obj.put(PGmObject.PHYSICS_DAMPING_ANGULAR, Double.parseDouble(data)); break; //$NON-NLS-1$
				// NOTE: Some versions of the format did not have all of the physics properties.
				// It is the same for GMK 820/821 as well.
				case "PhysicsObjectFriction": obj.put(PGmObject.PHYSICS_FRICTION, Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsObjectAwake": obj.put(PGmObject.PHYSICS_AWAKE, Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "PhysicsObjectKinematic": obj.put(PGmObject.PHYSICS_KINEMATIC, Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "point": //$NON-NLS-1$
					String[] coords = data.split(","); //$NON-NLS-1$
					obj.shapePoints.add(new ShapePoint(Double.parseDouble(coords[0]),
							Double.parseDouble(coords[1])));
					break;
				}
			}
		}

	private static void readRoom(ProjectFileContext c, ResNode node, String cNode)
		{
		final ProjectFile f = c.f;

		//ResourceReference<Room> r = c.rmids.get(i); //includes ID
		//Room rmn = r.get();
		//f.resMap.getList(Room.class).add(rmn);
		Room rmn = f.resMap.getList(Room.class).add();

		String fileName = new File(Util.getPOSIXPath(cNode)).getName();
		rmn.setName(fileName);
		ResNode rnode = new ResNode(rmn.getName(),ResNode.STATUS_SECONDARY,Room.class,rmn.reference);
		node.add(rnode);
		String path = f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		XMLEventReader reader = parseDocumentChecked2(f, path + ".room.gmx");
		if (reader == null) return;

		boolean makerSettings = false;
		int bkgnum = 0, viewnum = 0;
		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement())
				{
				if (nextEvent.isEndElement() && nextEvent.asEndElement().getName().getLocalPart().equals("makerSettings"))
					makerSettings = false;
				continue;
				}
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (nextEvent.isCharacters())
					data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			if (makerSettings)
				{
				switch (scope)
					{
					case "isSet": rmn.put(PRoom.REMEMBER_WINDOW_SIZE,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "w": rmn.put(PRoom.EDITOR_WIDTH,Integer.parseInt(data)); break; //$NON-NLS-1$
					case "h": rmn.put(PRoom.EDITOR_HEIGHT,Integer.parseInt(data)); break; //$NON-NLS-1$
					case "showGrid": rmn.put(PRoom.SHOW_GRID,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "showObjects": rmn.put(PRoom.SHOW_OBJECTS,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "showTiles": rmn.put(PRoom.SHOW_TILES,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "showBackgrounds": rmn.put(PRoom.SHOW_BACKGROUNDS,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "showForegrounds": rmn.put(PRoom.SHOW_FOREGROUNDS,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "showViews": rmn.put(PRoom.SHOW_VIEWS,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "deleteUnderlyingObj": rmn.put(PRoom.DELETE_UNDERLYING_OBJECTS,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "deleteUnderlyingTiles": rmn.put(PRoom.DELETE_UNDERLYING_TILES,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
					case "page": rmn.put(PRoom.CURRENT_TAB,Integer.parseInt(data)); break; //$NON-NLS-1$
					case "xoffset": rmn.put(PRoom.SCROLL_BAR_X,Integer.parseInt(data)); break; //$NON-NLS-1$
					case "yoffset": rmn.put(PRoom.SCROLL_BAR_Y,Integer.parseInt(data)); break; //$NON-NLS-1$
					}
				continue;
				}

			switch (scope)
				{
				case "caption": rmn.put(PRoom.CAPTION,data); break; //$NON-NLS-1$
				case "width": rmn.put(PRoom.WIDTH,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "height": rmn.put(PRoom.HEIGHT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "vsnap": rmn.put(PRoom.SNAP_X,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "hsnap": rmn.put(PRoom.SNAP_Y,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "isometric": rmn.put(PRoom.ISOMETRIC,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "speed": rmn.put(PRoom.SPEED,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "persistent": rmn.put(PRoom.PERSISTENT,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "colour": rmn.put(PRoom.BACKGROUND_COLOR,Util.convertGmColor(Integer.parseInt(data))); break; //$NON-NLS-1$
				case "showcolour": rmn.put(PRoom.DRAW_BACKGROUND_COLOR,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "code": rmn.put(PRoom.CREATION_CODE,data); break; //$NON-NLS-1$
				case "enableViews": rmn.put(PRoom.VIEWS_ENABLED,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "clearViewBackgrounds": rmn.put(PRoom.VIEWS_CLEAR,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "PhysicsWorld": rmn.put(PRoom.PHYSICS_WORLD,Integer.parseInt(data) != 0); break; //$NON-NLS-1$
				case "PhysicsWorldTop": rmn.put(PRoom.PHYSICS_TOP,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "PhysicsWorldLeft": rmn.put(PRoom.PHYSICS_LEFT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "PhysicsWorldRight": rmn.put(PRoom.PHYSICS_RIGHT,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "PhysicsWorldBottom": rmn.put(PRoom.PHYSICS_BOTTOM,Integer.parseInt(data)); break; //$NON-NLS-1$
				case "PhysicsWorldGravityX": rmn.put(PRoom.PHYSICS_GRAVITY_X,Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsWorldGravityY": rmn.put(PRoom.PHYSICS_GRAVITY_Y,Double.parseDouble(data)); break; //$NON-NLS-1$
				case "PhysicsWorldPixToMeters": rmn.put(PRoom.PHYSICS_PIXTOMETERS,Double.parseDouble(data)); break; //$NON-NLS-1$
				case "makerSettings": makerSettings = true; break; //$NON-NLS-1$
				case "background": //$NON-NLS-1$
					{
					final BackgroundDef bkg = rmn.backgroundDefs.get(bkgnum++);
					PropertyMap<PBackgroundDef> props = bkg.properties;
					Iterator<Attribute> atts = sel.getAttributes();
					while (atts.hasNext())
						{
						Attribute att = atts.next();
						switch (att.getName().getLocalPart())
							{
							case "visible": props.put(PBackgroundDef.VISIBLE,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "name": //$NON-NLS-1$
								String bkgname = att.getValue();
								postpone.add(new DefaultPostponedRef<>(f.resMap.getList(Background.class), bkg.properties, PBackgroundDef.BACKGROUND, bkgname));
								break;
							case "foreground": props.put(PBackgroundDef.FOREGROUND,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "htiled": props.put(PBackgroundDef.TILE_HORIZ,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "vtiled": props.put(PBackgroundDef.TILE_VERT,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "stretch": props.put(PBackgroundDef.STRETCH,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "hspeed": props.put(PBackgroundDef.H_SPEED,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "vspeed": props.put(PBackgroundDef.V_SPEED,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "x": props.put(PBackgroundDef.X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "y": props.put(PBackgroundDef.Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							}
						}
					}
					break;
				case "view": //$NON-NLS-1$
					{
					final View vw = rmn.views.get(viewnum++);
					PropertyMap<PView> props = vw.properties;
					Iterator<Attribute> atts = sel.getAttributes();
					while (atts.hasNext())
						{
						Attribute att = atts.next();
						switch (att.getName().getLocalPart())
							{
							case "visible": props.put(PView.VISIBLE,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "objName": //$NON-NLS-1$
								String objname = att.getValue();
								postpone.add(new DefaultPostponedRef<>(f.resMap.getList(GmObject.class), vw.properties, PView.OBJECT, objname));
								break;
							case "hspeed": props.put(PView.SPEED_H,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "vspeed": props.put(PView.SPEED_V,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "hborder": props.put(PView.BORDER_H,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "vborder": props.put(PView.BORDER_V,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "hport": props.put(PView.PORT_H,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "wport": props.put(PView.PORT_W,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "xport": props.put(PView.PORT_X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "yport": props.put(PView.PORT_Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "hview": props.put(PView.VIEW_H,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "wview": props.put(PView.VIEW_W,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "xview": props.put(PView.VIEW_X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "yview": props.put(PView.VIEW_Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							}
						}
					}
					break;
				case "instance": //$NON-NLS-1$
					{
					Instance inst = rmn.addInstance();
					PropertyMap<PInstance> props = inst.properties;
					Iterator<Attribute> atts = sel.getAttributes();
					while (atts.hasNext())
						{
						Attribute att = atts.next();
						switch (att.getName().getLocalPart())
							{
							case "x": props.put(PInstance.X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "y": props.put(PInstance.Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "objName": //$NON-NLS-1$
								// TODO: Replace this with DelayedRef
								// because of the way this is set up, sprites must be loaded before objects
								GmObject temp = f.resMap.getList(GmObject.class).get(att.getValue());
								if (temp != null) inst.properties.put(PInstance.OBJECT,temp.reference);
								break;
							case "name": props.put(PInstance.NAME,att.getValue()); break; //$NON-NLS-1$
							case "id"://$NON-NLS-1$
								// NOTE: Because LGM still supports GMK, we attempt to preserve the ID which Studio
								// will remove if it saves over the GMX, so see if the "id" attribute we added is
								// there otherwise make up a new ID.
								int instid = Integer.parseInt(att.getValue());
								if (instid > f.lastInstanceId)
									f.lastInstanceId = instid;
								inst.properties.put(PInstance.ID, instid);
								break;
							case "rotation": props.put(PInstance.ROTATION,Double.parseDouble(att.getValue())); break; //$NON-NLS-1$
							case "locked": props.put(PInstance.LOCKED,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "scaleX": props.put(PInstance.SCALE_X,Double.parseDouble(att.getValue())); break; //$NON-NLS-1$
							case "scaleY": props.put(PInstance.SCALE_Y,Double.parseDouble(att.getValue())); break; //$NON-NLS-1$
							case "colour": //$NON-NLS-1$
								long col = Long.parseLong(att.getValue()); //$NON-NLS-1$
								Color color = Util.convertInstanceColorWithAlpha((int) col);
								inst.setColor(color);
								inst.setAlpha(color.getAlpha());
								break;
							case "code": props.put(PInstance.CREATION_CODE,att.getValue()); break; //$NON-NLS-1$
							}
						}
					break;
					}
				case "tile": //$NON-NLS-1$
					{
					final Tile tile = new Tile(rmn);
					PropertyMap<PTile> props = tile.properties;
					Iterator<Attribute> atts = sel.getAttributes();
					while (atts.hasNext())
						{
						Attribute att = atts.next();
						switch (att.getName().getLocalPart())
							{
							case "x": props.put(PTile.ROOM_X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "y": props.put(PTile.ROOM_Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "bgName": //$NON-NLS-1$
								postpone.add(
										new DefaultPostponedRef<>(
												f.resMap.getList(Background.class), tile.properties, PTile.BACKGROUND, att.getValue()));
								break;
							case "name": props.put(PTile.NAME,att.getValue()); break; //$NON-NLS-1$
							case "id"://$NON-NLS-1$
								int tileid = Integer.parseInt(att.getValue());
								if (tileid > f.lastTileId)
									f.lastTileId = tileid;
								tile.properties.put(PTile.ID,tileid);
								break;
							case "xo": props.put(PTile.BG_X,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "yo": props.put(PTile.BG_Y,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "w": props.put(PTile.WIDTH,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "h": props.put(PTile.HEIGHT,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "depth": props.put(PTile.DEPTH,Integer.parseInt(att.getValue())); break; //$NON-NLS-1$
							case "locked": props.put(PTile.LOCKED,Integer.parseInt(att.getValue()) != 0); break; //$NON-NLS-1$
							case "scaleX": props.put(PTile.SCALE_X,Double.parseDouble(att.getValue())); break; //$NON-NLS-1$
							case "scaleY": props.put(PTile.SCALE_Y,Double.parseDouble(att.getValue())); break; //$NON-NLS-1$
							case "colour": props.put(PTile.COLOR,Long.parseLong(att.getValue())); break; //$NON-NLS-1$
							}
						}
					break;
					}
				}
			}
		}

	private static void readInclude(ProjectFileContext c, ResNode node, String cNode)
		{

		}

	private static void readPackages(ProjectFileContext c, ResNode root)
		{

		}

	private static void readConstants(Constants cnsts, String node)
		{

		}

	private static void readDefaultConstants(ProjectFileContext c, ResNode root)
		{

		}

	private static void readGameInformation(ProjectFileContext c, String cNode)
		{
		GameInformation gameInfo = c.f.gameInfo;

		String path = c.f.getDirectory() + '/' + Util.getPOSIXPath(cNode);

		String text = ""; //$NON-NLS-1$

		try (BufferedReader reader = new BufferedReader(new FileReader(path)))
			{
			String line = ""; //$NON-NLS-1$
			while ((line = reader.readLine()) != null)
				{
				text += line + '\n';
				}
			}
		catch (FileNotFoundException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "file not found: " + path, e));
			}
		catch (IOException e)
			{
			interfaceProvider.handleException(new GmFormatException(c.f, "unable to read file: " + path, e));
			}

		gameInfo.put(PGameInformation.TEXT,text);
		}

	private static void readActions(ProjectFileContext c, ActionContainer container, String errorKey,
			int format1, int format2, XMLEventReader reader)
		{
		final ProjectFile f = c.f;

		int libid = 0;
		int actid = 0;
		byte kind = 0;
		boolean userelative = false;
		boolean isquestion = false;
		boolean isquestiontrue = false;
		boolean isrelative = false;
		boolean useapplyto = false;
		byte exectype = 0;

		String appliesto = ""; //$NON-NLS-1$
		String functionname = ""; // execInfo for if the action just calls an action function //$NON-NLS-1$
		String codestring = ""; // execInfo for if the action executes code //$NON-NLS-1$

		Argument[] args = new Argument[0];

		LibAction la = null;

		while (reader.hasNext())
			{
			XMLEvent nextEvent = null;
			try
				{
				nextEvent = reader.nextEvent();
				}
			catch (XMLStreamException e)
				{
				// TODO Auto-generated catch block
				e.printStackTrace();
				}

			if (!nextEvent.isStartElement())
				{
				if (nextEvent.isEndElement() && nextEvent.asEndElement().getName().getLocalPart().equals("action"))
					{
					la = LibManager.getLibAction(libid,actid);
					boolean unknownLib = la == null;
					// The libAction will have a null parent, among other things
					if (unknownLib)
						{
						la = new LibAction();
						la.id = actid;
						la.parentId = libid;
						la.actionKind = kind;
						// TODO: Maybe make this more agnostic?
						if (la.actionKind == Action.ACT_CODE)
							{
							la = LibManager.codeAction;
							}
						else
							{
							la.allowRelative = userelative;
							la.question = isquestion;
							la.canApplyTo = useapplyto;
							la.execType = exectype;
							if (la.execType == Action.EXEC_FUNCTION) la.execInfo = functionname;
							if (la.execType == Action.EXEC_CODE) la.execInfo = codestring;
							}
						if (args != null)
							{
							la.libArguments = new LibArgument[args.length];
							for (int b = 0; b < args.length; b++)
								{
								LibArgument argument = new LibArgument();
								argument.kind = args[b].kind;
								la.libArguments[b] = argument;
								}
							}
						}

					final Action act = container.addAction(la);
					if (appliesto.equals("self")) //$NON-NLS-1$
						{
						act.setAppliesTo(GmObject.OBJECT_SELF);
						}
					else if (appliesto.equals("other")) //$NON-NLS-1$
						{
						act.setAppliesTo(GmObject.OBJECT_OTHER);
						}
					else
						{
						final String objname = appliesto;
						PostponedRef pr = new PostponedRef()
							{
								public boolean invoke()
									{
									ResourceList<GmObject> list = f.resMap.getList(GmObject.class);
									if (list == null)
										{
										return false;
										}
									GmObject obj = list.get(objname);
									if (obj == null)
										{
										return false;
										}
									act.setAppliesTo(obj.reference);
									return true;
									}
							};
						postpone.add(pr);
						}

					act.setRelative(isrelative);
					if (args != null && args.length > 0)
						{
						act.setArguments(args);
						}
					act.setNot(isquestiontrue);
					}
				continue;
				}
			StartElement sel = nextEvent.asStartElement();
			String scope = sel.getName().getLocalPart();
			if (!reader.hasNext()) break;
			String data = "";
			try
				{
				nextEvent = reader.nextEvent();
				if (nextEvent.isCharacters())
					data = nextEvent.asCharacters().getData();
				}
			catch (XMLStreamException e1)
				{
				// TODO Auto-generated catch block
				e1.printStackTrace();
				}

			switch (scope)
				{
				case "libid": libid = Integer.parseInt(data); break; //$NON-NLS-1$
				case "id": actid = Integer.parseInt(data); break; //$NON-NLS-1$
				case "kind": kind = Byte.parseByte(data); break; //$NON-NLS-1$
				case "userrelative": userelative = Integer.parseInt(data) != 0; break; //$NON-NLS-1$
				case "relative": isrelative = Integer.parseInt(data) != 0; break; //$NON-NLS-1$
				case "isquestion": isquestion = Integer.parseInt(data) != 0; break; //$NON-NLS-1$
				case "isnot": isquestiontrue = Integer.parseInt(data) != 0; break; //$NON-NLS-1$
				case "useapplyto": useapplyto = Integer.parseInt(data) != 0; break; //$NON-NLS-1$
				case "exetype": exectype = Byte.parseByte(data); break; //$NON-NLS-1$
				case "whoName": appliesto = data; break; //$NON-NLS-1$
				case "functionname": functionname = data; break; //$NON-NLS-1$
				case "codestring": codestring = data; break; //$NON-NLS-1$
				}
			}
		}
	}

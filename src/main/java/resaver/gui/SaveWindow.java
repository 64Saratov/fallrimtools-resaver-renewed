/*
 * Copyright 2016 Mark.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package resaver.gui;

import resaver.ProgressModel;
import resaver.Game;
import java.util.Set;
import java.awt.*;
import java.awt.event.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.plaf.basic.BasicComboBoxRenderer;
import javax.swing.text.Document;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import javax.swing.tree.TreePath;
import mf.Duad;
import mf.JValueMenuItem;
import resaver.*;
import resaver.ess.*;
import resaver.ess.papyrus.*;
import resaver.gui.FilterTreeModel.Node;
import resaver.pex.AssemblyLevel;
import resaver.pex.PexFile;
import resaver.gui.FilterFactory.ParseLevel;

/**
 * A window that displays savegame data and allows limited editing.
 *
 * @author Mark Fairchild
 */
@SuppressWarnings("serial")
final public class SaveWindow extends JFrame {

    /**
     * Create a new <code>SaveWindow</code> with a <code>Path</code>. If the
     * <code>Path</code> is a savefile, it will be opened.
     *
     * @param path The <code>Path</code> to open.
     * @param autoParse Automatically parse the specified savefile.
     */
    public SaveWindow(Path path, boolean autoParse) {
        super.setExtendedState(PREFS.getInt("settings.extendedState", JFrame.MAXIMIZED_BOTH));

        this.JFXPANEL = PREFS.getBoolean("settings.javafx", true)
                ? this.initializeJavaFX()
                : null;

        this.TIMER = new mf.Timer("SaveWindow timer");
        this.TIMER.start();
        LOG.info("Created timer.");

        this.save = null;
        this.analysis = Optional.empty();
        this.filter = (x -> true);
        this.scanner = null;
        
        this.TREE = new FilterTree();
        this.TREESCROLLER = new JScrollPane(this.TREE);
        this.TOPPANEL = new JPanel();
        this.MODPANEL = new JPanel(new FlowLayout(FlowLayout.LEADING));
        this.MODLABEL = new JLabel("Фильтр модов:");
        this.MODCOMBO = new JComboBox<>();
        this.PLUGINCOMBO = new JComboBox<>();
        this.FILTERFIELD = new JTreeFilterField(() -> updateFilters(false), PREFS.get("settings.regex", ""));
        this.FILTERPANEL = new JPanel(new FlowLayout(FlowLayout.LEADING));
        this.MAINPANEL = new JPanel(new BorderLayout());
        this.STATUSPANEL2 = new JPanel();
        
        this.PROGRESSPANEL = new JPanel();
        this.PROGRESSPANEL.setLayout(new BoxLayout(this.PROGRESSPANEL, BoxLayout.Y_AXIS));
        
        this.STATUSPANEL1 = new JPanel(new BorderLayout());
        this.TREEHISTORY = new JTreeHistory(this.TREE);

        this.TABLE = new VariableTable(this);
        this.INFOPANE = new InfoPane(null, e -> this.hyperlinkUpdate(e));

        this.DATASCROLLER = new JScrollPane(this.TABLE);
        this.INFOSCROLLER = new JScrollPane(this.INFOPANE);
        this.RIGHTSPLITTER = new JSplitPane(JSplitPane.VERTICAL_SPLIT, this.INFOSCROLLER, this.DATASCROLLER);
        this.MAINSPLITTER = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, this.MAINPANEL, this.RIGHTSPLITTER);
        this.MENUBAR = new JMenuBar();
        this.MENU_FILE = new JMenu("Файл");
        this.MENU_FILTER = new JMenu("Фильтр");
        this.MENU_CLEAN = new JMenu("Очистка");
        this.MENU_OPTIONS = new JMenu("Настройки");
        this.MENU_DATA = new JMenu("Данные");
        this.MENU_HELP = new JMenu("Справка");

        this.MI_EXIT = new JMenuItem("Выход", KeyEvent.VK_E);
        this.MI_LOAD = new JMenuItem("Открыть", KeyEvent.VK_O);
        this.MI_LOADESPS = new JMenuItem("Разобрать ESP/ESM.", KeyEvent.VK_P);
        this.MI_SAVE = new JMenuItem("Сохранить", KeyEvent.VK_S);
        this.MI_SAVEAS = new JMenuItem("Сохранить как", KeyEvent.VK_A);
        this.MI_EXPORTPLUGINS = new JMenuItem("Экспортировать список плагинов", KeyEvent.VK_X);
        this.MI_SETTINGS = new JMenuItem("Параметры");
        this.MI_WATCHSAVES = new JCheckBoxMenuItem("Следить за папкой сохранений", PREFS.getBoolean("settings.watch", false));

        this.MI_USEMO2 = new JCheckBoxMenuItem("Интеграция с Mod Organizer 2", PREFS.getBoolean("settings.useMO2", false));
        
        this.MI_SHOWUNATTACHED = new JCheckBoxMenuItem("Показывать неподключённые экземпляры", PREFS.getBoolean("settings.showUnattached", false));
        this.MI_SHOWUNDEFINED = new JCheckBoxMenuItem("Показывать неопределённые элементы", PREFS.getBoolean("settings.showUndefined", false));
        this.MI_SHOWNOPARENTS = new JCheckBoxMenuItem("Показывать скрипты без родителя", PREFS.getBoolean("settings.showNoParents", false));
        this.MI_SHOWMISSINGPARENTS = new JCheckBoxMenuItem("Показывать скрипты с отсутствующим родителем", PREFS.getBoolean("settings.showMissingParents", false));
        
        this.MI_SHOWMEMBERLESS = new JCheckBoxMenuItem("Показывать экземпляры без участников", PREFS.getBoolean("settings.showMemberless", false));
        this.MI_SHOWCANARIES = new JCheckBoxMenuItem("Показывать обнулённые канарейки", PREFS.getBoolean("settings.showCanaries", false));
        this.MI_SHOWNULLREFS = new JCheckBoxMenuItem("Показывать формлисты с пустыми ссылками", PREFS.getBoolean("settings.showNullrefs", false));
        
        this.MI_SHOWPARSED0 = new JRadioButtonMenuItem("Игнорировать", true);
        this.MI_SHOWPARSED1 = new JRadioButtonMenuItem("Показывать полностью разобранные ChangeForms", false);
        this.MI_SHOWPARSED2 = new JRadioButtonMenuItem("Показывать частично разобранные ChangeForms", false);
        this.MI_SHOWPARSED3 = new JRadioButtonMenuItem("Показывать неразобранные ChangeForms", false);
        
        this.MI_SHOWNONEXISTENTCREATED = new JCheckBoxMenuItem("Показывать экземпляры несуществующих форм", PREFS.getBoolean("settings.showNonexistent", false));
        this.MI_SHOWLONGSTRINGS = new JCheckBoxMenuItem("Показывать длинные строки (от 512 симв.)", PREFS.getBoolean("settings.showLongStrings", false));
        this.MI_SHOWDELETED = new JCheckBoxMenuItem("Показывать changeforms cell(-1)", PREFS.getBoolean("settings.showDeleted", false));
        this.MI_SHOWEMPTY = new JCheckBoxMenuItem("Показывать пустые REFR", PREFS.getBoolean("settings.showEmpty", false));
        this.MI_SHOWSCRIPTATTACHED = new JCheckBoxMenuItem("Показывать формы со скриптами", PREFS.getBoolean("settings.showAttached", false));
        this.MI_CHANGEFILTER = new JValueMenuItem<>("Фильтр ChangeFlag (%s)", null);
        this.MI_CHANGEFORMFILTER = new JValueMenuItem<>("Фильтр ChangeFormFlag (%s)", null);
        this.MI_CHANGEFORMCONTENTFILTER = new JValueMenuItem<>("Фильтр содержимого ChangeForm (%s)", "");
        this.MI_CHANGEFORMCONTENTFILTER.setValue(PREFS.get("settings.cfc_filter", ""));
        
        this.MI_REMOVEUNATTACHED = new JMenuItem("Удалить неподключённые экземпляры", KeyEvent.VK_1);
        this.MI_REMOVEUNDEFINED = new JMenuItem("Удалить неопределённые элементы", KeyEvent.VK_2);
        this.MI_RESETHAVOK = new JMenuItem("Сбросить Havok", KeyEvent.VK_3);
        this.MI_CLEANSEFORMLISTS = new JMenuItem("Очистить формлисты", KeyEvent.VK_4);
        this.MI_REMOVENONEXISTENT = new JMenuItem("Удалить экземпляры несуществующих форм", KeyEvent.VK_5);
        this.MI_BATCHCLEAN = new JMenuItem("Пакетная очистка", KeyEvent.VK_6);
        this.MI_KILL = new JMenuItem("Завершить выбранные");

        this.MI_SHOWMODS = new JCheckBoxMenuItem("Показывать поле фильтра модов", PREFS.getBoolean("settings.showMods", false));

        this.MI_LOOKUPID = new JMenuItem("Найти ID по имени");
        this.MI_LOOKUPBASE = new JMenuItem("Найти базовый объект/NPC");
        this.MI_ANALYZE_ARRAYS = new JMenuItem("Разобрать блок массивов");
        this.MI_COMPARETO = new JMenuItem("Сравнить с");

        this.MI_SHOWLOG = new JMenuItem("Показать журнал", KeyEvent.VK_S);
        this.MI_ABOUT = new JMenuItem("О программе", KeyEvent.VK_A);
        this.MI_SUPPORT = new JMenuItem("Поддержка");

        this.BTN_CLEAR_FILTER = new JButton("Сбросить фильтры");
        this.LOGWINDOW = new LogWindow();
        this.LBL_MEMORY = new MemoryLabel();
        this.LBL_WATCHING = new JLabel("ОТСЛЕЖИВАНИЕ");
        this.LBL_SCANNING = new JLabel("СКАНИРОВАНИЕ");
        this.WATCHER = new Watcher(this, Optional.empty());

        this.initComponents(path, autoParse);

        TIMER.stop();
        LOG.info(String.format("Version: %s", AboutDialog.getVersion()));
        LOG.info(String.format("SaveWindow constructed; took %s.", this.TIMER.getFormattedTime()));
    }

    /**
     * Initialize the swing and AWT components.
     *
     * @param path The <code>Path</code> to open.
     * @param autoParse Automatically parse the specified savefile.
     *
     */
    private void initComponents(Path path, boolean autoParse) {
        this.resetTitle(null);
        this.setDropTarget(new ReSaver_RenewedDropTarget(f -> open(f, PREFS.getBoolean("settings.alwaysParsePlugins", true))));
        this.TREE.addTreeSelectionListener(e -> updateContextInformation());
        this.DATASCROLLER.setBorder(BorderFactory.createTitledBorder(this.DATASCROLLER.getBorder(), "Данные"));
        this.INFOSCROLLER.setBorder(BorderFactory.createTitledBorder(this.INFOSCROLLER.getBorder(), "Информация"));
        this.MAINSPLITTER.setResizeWeight(0.5);
        this.MAINSPLITTER.setDividerLocation(0.5);
        this.RIGHTSPLITTER.setResizeWeight(0.66);
        this.RIGHTSPLITTER.setDividerLocation(0.5);
        this.MAINPANEL.setMinimumSize(new Dimension(350, 400));
        this.PLUGINCOMBO.setRenderer(new PluginListCellRenderer());
        this.PLUGINCOMBO.setPrototypeDisplayValue(Plugin.PROTOTYPE);
        this.PLUGINCOMBO.setToolTipText("Выберите плагин для фильтрации.");

        AutoCompletion.enable(this.PLUGINCOMBO);
        AutoCompletion.enable(this.MODCOMBO);

        this.STATUSPANEL2.add(this.LBL_MEMORY);
        this.STATUSPANEL2.add(this.LBL_WATCHING);
        this.STATUSPANEL2.add(this.LBL_SCANNING);
        this.STATUSPANEL2.add(this.PROGRESSPANEL);

        this.STATUSPANEL1.add(this.STATUSPANEL2, BorderLayout.LINE_START);
        this.STATUSPANEL1.add(this.TREEHISTORY, BorderLayout.LINE_END);

        //this.TREESCROLLER.getViewport().putClientProperty("EnableWindowBlit", Boolean.TRUE);
        this.TREESCROLLER.getViewport().setScrollMode(JViewport.BACKINGSTORE_SCROLL_MODE);
                
        this.FILTERPANEL.add(this.FILTERFIELD);
        this.FILTERPANEL.add(this.PLUGINCOMBO);
        this.FILTERPANEL.add(this.BTN_CLEAR_FILTER);

        this.MODPANEL.add(this.MODLABEL);
        this.MODPANEL.add(this.MODCOMBO);
        this.MODPANEL.setVisible(false);
        this.MODCOMBO.setRenderer(new ModListCellRenderer());

        this.TOPPANEL.setLayout(new BoxLayout(this.TOPPANEL, BoxLayout.Y_AXIS));
        this.TOPPANEL.add(this.MODPANEL);
        this.TOPPANEL.add(this.FILTERPANEL);

        this.MAINPANEL.add(this.TREESCROLLER, BorderLayout.CENTER);
        this.MAINPANEL.add(this.TOPPANEL, BorderLayout.PAGE_START);
        this.MAINPANEL.add(this.STATUSPANEL1, BorderLayout.PAGE_END);

        this.MENU_FILE.setMnemonic('f');
        fillMenu(MENU_FILE, 
                this.MI_LOAD,
                MI_SAVE,
                MI_SAVEAS,
                null,
                MI_LOADESPS,
                MI_WATCHSAVES,
                null,
                MI_EXPORTPLUGINS,
                null,
                MI_EXIT);

        final JMenu PARSE_MENU = new JMenu("ChangeForms");
        groupMenuItems(MI_SHOWPARSED0, MI_SHOWPARSED1, MI_SHOWPARSED2, MI_SHOWPARSED3);
        fillMenu(PARSE_MENU, 
                MI_SHOWPARSED0, 
                MI_SHOWPARSED1, 
                MI_SHOWPARSED2, 
                MI_SHOWPARSED3);
        
        //this.MENU_FILTER.add(PARSE_MENU);        
        fillMenu(MENU_FILTER, 
                MI_SHOWUNATTACHED, 
                MI_SHOWUNDEFINED,
                MI_SHOWNOPARENTS,
                MI_SHOWMISSINGPARENTS,
                null,
                MENU_FILTER, 
                MI_SHOWMEMBERLESS, 
                MI_SHOWCANARIES, 
                MI_SHOWNULLREFS, 
                MI_SHOWNONEXISTENTCREATED, 
                MI_SHOWLONGSTRINGS, 
                MI_SHOWDELETED, 
                MI_SHOWEMPTY,
                null,
                PARSE_MENU,
                MI_SHOWSCRIPTATTACHED,
                MI_CHANGEFILTER,
                MI_CHANGEFORMFILTER,
                MI_CHANGEFORMCONTENTFILTER);
     
        MENU_CLEAN.setMnemonic('c');
        fillMenu(MENU_CLEAN,
            MI_REMOVEUNATTACHED,
            MI_REMOVEUNDEFINED,
            MI_RESETHAVOK,
            MI_CLEANSEFORMLISTS,
            MI_REMOVENONEXISTENT,
            null,
            MI_BATCHCLEAN,
            MI_KILL);
        
        MENU_OPTIONS.setMnemonic('o');
        fillMenu(MENU_OPTIONS, MI_USEMO2, MI_SHOWMODS, MI_SETTINGS);

        SortingMethod sort = getSorting();
        JRadioButtonMenuItem miAlpha = new JRadioButtonMenuItem("По алфавиту", sort==SortingMethod.ALPHA);
        JRadioButtonMenuItem miSize = new JRadioButtonMenuItem("По размеру", sort==SortingMethod.SIZE);
        JRadioButtonMenuItem miMass = new JRadioButtonMenuItem("По массе", sort==SortingMethod.MASS);
        JRadioButtonMenuItem miNone = new JRadioButtonMenuItem("Нет", sort==SortingMethod.NONE);

        miAlpha.addActionListener(e -> setSorting(SortingMethod.ALPHA));
        miSize.addActionListener(e -> setSorting(SortingMethod.SIZE));        
        miMass.addActionListener(e -> setSorting(SortingMethod.MASS));
        miNone.addActionListener(e -> setSorting(SortingMethod.NONE));
        groupMenuItems(miAlpha, miSize, miMass, miNone);

        miAlpha.setToolTipText("Простая сортировка по алфавиту");
        miSize.setToolTipText("Сортировка по размеру каждого элемента и по количеству элементов в группе.");
        miMass.setToolTipText("Очень медленно и очень сыро.");
        miNone.setToolTipText("Быстро.");

        JMenu MENU_SORT = new JMenu("Сортировка");
        fillMenu(MENU_SORT, miAlpha, miSize, miMass, miNone);
        
        this.MENU_DATA.setMnemonic('d');
        fillMenu(MENU_DATA, MENU_SORT, MI_LOOKUPID, MI_LOOKUPBASE, MI_ANALYZE_ARRAYS, MI_COMPARETO);

        MI_LOOKUPID.setEnabled(false);
        MI_LOOKUPBASE.setEnabled(false);
        MI_LOADESPS.setEnabled(false);

        MENU_HELP.setMnemonic('h');
        fillMenu(MENU_HELP, MI_SHOWLOG, MI_ABOUT, MI_SUPPORT);

        this.MENUBAR.add(this.MENU_FILE);
        this.MENUBAR.add(this.MENU_FILTER);
        this.MENUBAR.add(this.MENU_CLEAN);
        this.MENUBAR.add(this.MENU_OPTIONS);
        this.MENUBAR.add(this.MENU_DATA);
        this.MENUBAR.add(this.MENU_HELP);

        this.MI_EXIT.addActionListener(e -> exitWithPrompt());
        this.MI_LOAD.addActionListener(e -> openWithPrompt());
        this.MI_LOADESPS.addActionListener(e -> scanESPs(true));
        this.MI_WATCHSAVES.addActionListener(e -> PREFS.putBoolean("settings.watch", MI_WATCHSAVES.isSelected()));
        this.MI_WATCHSAVES.addActionListener(e -> setWatching(MI_WATCHSAVES.isSelected()));
        this.MI_SAVE.addActionListener(e -> save(false, null));
        this.MI_SAVEAS.addActionListener(e -> save(true, null));
        this.MI_EXPORTPLUGINS.addActionListener(e -> exportPlugins());
        this.MI_SETTINGS.addActionListener(e -> showSettings());
        this.MI_SHOWUNATTACHED.addActionListener(e -> updateFilters(false));
        this.MI_SHOWUNDEFINED.addActionListener(e -> updateFilters(false));
        this.MI_SHOWNOPARENTS.addActionListener(e -> updateFilters(false));
        this.MI_SHOWMISSINGPARENTS.addActionListener(e -> updateFilters(false));
        this.MI_SHOWMEMBERLESS.addActionListener(e -> updateFilters(false));
        this.MI_SHOWCANARIES.addActionListener(e -> updateFilters(false));
        this.MI_SHOWNULLREFS.addActionListener(e -> updateFilters(false));
        this.MI_SHOWPARSED0.addActionListener(e -> updateFilters(false));
        this.MI_SHOWPARSED1.addActionListener(e -> updateFilters(false));
        this.MI_SHOWPARSED2.addActionListener(e -> updateFilters(false));
        this.MI_SHOWPARSED3.addActionListener(e -> updateFilters(false));
        this.MI_SHOWNONEXISTENTCREATED.addActionListener(e -> updateFilters(false));
        this.MI_SHOWLONGSTRINGS.addActionListener(e -> updateFilters(false));
        this.MI_SHOWDELETED.addActionListener(e -> updateFilters(false));
        this.MI_SHOWEMPTY.addActionListener(e -> updateFilters(false));
        this.MI_SHOWSCRIPTATTACHED.addActionListener(e -> updateFilters(false));
        this.MI_CHANGEFILTER.addActionListener(e -> setChangeFlagFilter());
        this.MI_CHANGEFORMFILTER.addActionListener(e -> setChangeFormFlagFilter());
        this.MI_CHANGEFORMCONTENTFILTER.addActionListener(e -> setChangeFormContentFilter());
        this.MI_REMOVEUNATTACHED.addActionListener(e -> cleanUnattached());
        this.MI_REMOVEUNDEFINED.addActionListener(e -> cleanUndefined());
        this.MI_RESETHAVOK.addActionListener(e -> resetHavok());
        this.MI_CLEANSEFORMLISTS.addActionListener(e -> cleanseFormLists());
        this.MI_REMOVENONEXISTENT.addActionListener(e -> cleanNonexistent());
        this.MI_BATCHCLEAN.addActionListener(e -> batchClean());
        this.MI_KILL.addActionListener(e -> kill());
        this.MI_SHOWMODS.addActionListener(e -> showModPanel(analysis));
        this.MI_SHOWMODS.addActionListener(e -> PREFS.putBoolean("settings.showMods", this.MI_SHOWMODS.isSelected()));
        this.MI_LOOKUPID.addActionListener(e -> lookupID());
        this.MI_LOOKUPBASE.addActionListener(e -> lookupBase());
        this.MI_ANALYZE_ARRAYS.addActionListener(e -> showDataAnalyzer(this.save.getPapyrus().getArraysBlock()));
        this.MI_COMPARETO.addActionListener(e -> compareTo());
        this.MI_SHOWLOG.addActionListener(e -> showLog());
        this.MI_ABOUT.addActionListener(e -> AboutDialog.show(this));
        this.MI_SUPPORT.addActionListener(e -> SupportDialog.show(this));
        this.MI_USEMO2.addActionListener(e -> PREFS.putBoolean("settings.useMO2", this.MI_USEMO2.isSelected()));

        this.MI_EXIT.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK));
        this.MI_LOAD.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
        this.MI_LOADESPS.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK));
        this.MI_SAVE.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
        this.MI_SAVEAS.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK));
        this.MI_BATCHCLEAN.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_6, KeyEvent.CTRL_DOWN_MASK));

        this.BTN_CLEAR_FILTER.addActionListener(e -> updateFilters(true));

        this.MODCOMBO.setToolTipText("Выберите мод для фильтрации.");
        this.LBL_MEMORY.setToolTipText("Текущее использование памяти.");
        this.LBL_WATCHING.setToolTipText("Когда вы сохраните игру, ReSaver автоматически откроет новый сейв.");
        this.LBL_SCANNING.setToolTipText("ReSaver сканирует ваши плагины, чтобы добавить правильные имена всему.");
        this.BTN_CLEAR_FILTER.setToolTipText("Сбросить все фильтры.");
        this.MI_ABOUT.setToolTipText("Показывает информацию о версии, о системе и оригинальную цветную фотографию котов.");
        this.MI_SHOWLOG.setToolTipText("Показать внутренний журнал ReSaver. Только для разработки.");
        this.MI_ANALYZE_ARRAYS.setToolTipText("Показывает анализатор данных для раздела «Массивы», который ещё не полностью расшифрован. Только для разработки.");
        this.MI_COMPARETO.setToolTipText("Сравнить текущий сейв с другим. Только для разработки.");

        this.MI_CHANGEFILTER.setToolTipText("Задаёт фильтр ChangeFlag. ChangeFlags описывают, какие изменения присутствуют в ChangeForms.");
        this.MI_CHANGEFORMFILTER.setToolTipText("Задаёт фильтр ChangeFormFlag. ChangeFormFlags — часть ChangeForm, изменяющая флаги формы. Подробнее эти флаги можно посмотреть в xEdit.");
        this.MI_CHANGEFORMCONTENTFILTER.setToolTipText("Задаёт фильтр содержимого ChangeForm. Для продвинутых!");
        this.MI_REMOVENONEXISTENT.setToolTipText("Удаляет ScriptInstances, привязанные к несуществующим ChangeForms. Такие экземпляры остаются, когда игровые объекты создаются и затем уничтожаются. Однако их очистка может привести к тому, что некоторые моды перестанут работать.");
        this.MI_REMOVEUNATTACHED.setToolTipText("Удаляет ScriptInstances, не привязанные ни к чему. Обычно они остаются после удаления модов. Однако в Fallout 4 они используются намеренно, поэтому удаляйте с осторожностью.");
        this.MI_REMOVEUNDEFINED.setToolTipText("Удаляет скрипты и ScriptInstances, у которых отсутствует сам скрипт, а также завершает связанные с ними ActiveScripts. SKSE и F4SE обычно убирают их автоматически; если этого не произошло, скорее всего, на то есть причина. Удаляйте с осторожностью.");

        final javax.swing.border.Border BORDER = BorderFactory.createCompoundBorder(BorderFactory.createLoweredBevelBorder(), BorderFactory.createEmptyBorder(1, 1, 1, 1));

        this.LBL_MEMORY.setBorder(BORDER);
        this.LBL_WATCHING.setBorder(BORDER);
        this.LBL_SCANNING.setBorder(BORDER);

        final java.awt.Font DEFFONT = this.LBL_MEMORY.getFont();
        final java.awt.Font MONO = new java.awt.Font(java.awt.Font.MONOSPACED, DEFFONT.getStyle(), DEFFONT.getSize());
        this.LBL_MEMORY.setFont(MONO);
        this.LBL_WATCHING.setFont(MONO);
        this.LBL_SCANNING.setFont(MONO);

        this.LBL_WATCHING.setVisible(false);
        this.LBL_SCANNING.setVisible(false);

        LOG.getParent().addHandler(this.LOGWINDOW.getHandler());

        this.setJMenuBar(this.MENUBAR);
        this.setContentPane(this.MAINSPLITTER);
        this.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        this.setPreferredSize(new java.awt.Dimension(800, 600));
        this.pack();
        SaveWindow.this.restoreWindowPosition();
        this.setLocationRelativeTo(null);

        this.MI_SHOWNONEXISTENTCREATED.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                final String WARN = "Некоторые моды используют несуществующие формы намеренно. Удаляйте их с осторожностью.";
                final String WARN_TITLE = "Предупреждение";
                JOptionPane.showMessageDialog(this, WARN, WARN_TITLE, JOptionPane.WARNING_MESSAGE);
            }
        });

        this.setWatching(this.MI_WATCHSAVES.isSelected());

        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent evt) {
                SaveWindow.this.exitWithPrompt();
            }

            @Override
            public void windowOpened(WindowEvent evt) {
                if (Configurator.validateSavegame(path)) {
                    boolean parse = autoParse || PREFS.getBoolean("settings.alwaysParsePlugins", true);
                    SaveWindow.this.open(path, parse);
                } else {
                    SaveWindow.this.open();
                }
            }
        });

        try {
            final java.io.InputStream INPUT = this.getClass().getClassLoader().getResourceAsStream("Disk.png");
            final Image ICON = javax.imageio.ImageIO.read(INPUT);
            super.setIconImage(ICON);
        } catch (IOException | NullPointerException | IllegalArgumentException ex) {
            LOG.warning("Не удалось загрузить значок.");
        }
        this.MODCOMBO.addItemListener(e -> updateFilters(false));
        this.PLUGINCOMBO.addItemListener(e -> updateFilters(false));
        this.TREE.setDeleteHandler(paths -> deletePaths(paths));
        this.TREE.setEditHandler(element -> editElement(element));
        this.TREE.setPurgeHandler(plugins -> purgePlugins(plugins, true, true));
        this.TREE.setDeleteFormsHandler((plugin) -> purgePlugins(Collections.singleton(plugin), false, true));
        this.TREE.setDeleteInstancesHandler((plugin) -> purgePlugins(Collections.singleton(plugin), true, false));
        this.TREE.setFilterPluginsHandler(plugin -> PLUGINCOMBO.setSelectedItem(plugin));
        this.TREE.setZeroThreadHandler(threads -> zeroThreads(threads));
        this.TREE.setFindHandler(element -> this.findElement(element));
        this.TREE.setFinder(element -> this.findOwner(element));
        this.TREE.setCleanseFLSTHandler(flst -> this.cleanseFormList(flst));
        this.TREE.setCompressionHandler(ct -> this.setCompressionType(ct));
        this.LBL_MEMORY.initialize();
    }

    /**
     * Clears the modified flag.
     *
     * @param saveFile A new value for the path.
     */
    void resetTitle(Path savefile) {
        this.modified = false;

        if (this.save == null) {
            final String TITLE = String.format("ReSaver Renewed %s: (сейв не загружен)", AboutDialog.getVersion());
            this.setTitle(TITLE);

        } else {
            // Get the filesize if possible. 
            float size;
            try {
                size = (float) Files.size(savefile) / 1048576.0f;
            } catch (IOException ex) {
                LOG.log(Level.WARNING, "Ошибка установки заголовка.", ex);
                size = Float.NEGATIVE_INFINITY;
            }

            // Get the file digest.
            final Long DIGEST = this.save.getDigest();

            // Make an abbreviated filename.
            String fullName = savefile.getFileName().toString();
            final int MAXLEN = 80;
            final String NAME = (fullName.length() > MAXLEN
                    ? (fullName.substring(0, MAXLEN) + "...")
                    : fullName);

            final String TITLE = String.format("ReSaver Renewed %s: %s (%1.2f МБ, digest = %08x)", AboutDialog.getVersion(), NAME, size, DIGEST);
            this.setTitle(TITLE);
        }
    }

    /**
     * Sets the modified flag.
     */
    void setModified() {
        this.modified = true;
    }

    /**
     * Sets the <code>Analysis</code>.
     *
     * @param newAnalysis The mod data, or null if there is no mod data
     * available.
     *
     */
    private void clearAnalysis() {
        this.analysis = Optional.empty();
        this.updateContextInformation();
        this.MI_LOOKUPID.setEnabled(false);
        this.MI_LOOKUPBASE.setEnabled(false);
        this.hideModPanel();
    }
    
    /**
     * Sets the <code>Analysis</code>.
     *
     * @param newAnalysis The mod data, or null if there is no mod data
     * available.
     *
     */
    private void setAnalysis(Analysis newAnalysis) {
        Objects.requireNonNull(newAnalysis);
        
        if (this.analysis.filter(newAnalysis::equals).isPresent()) {
            LOG.info("Reusing analysis.");            
        }
        
        this.analysis = Optional.of(newAnalysis);
        this.updateContextInformation();
        this.save.addNames(newAnalysis, this.save.getContext());
        this.MI_LOOKUPID.setEnabled(true);
        this.MI_LOOKUPBASE.setEnabled(true);   

        if (this.MI_SHOWMODS.isSelected()) {
            showModPanel(analysis);
        } else {
            hideModPanel();
        }
        
        this.refreshTree();
    }

    private void showModPanel(Optional<Analysis> analysis) {
        analysis.ifPresentOrElse(an -> {
            final Mod[] MODS = new Mod[an.MODS.size()];
            an.MODS.toArray(MODS);
            Arrays.sort(MODS, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

            DefaultComboBoxModel<Mod> modModel = new DefaultComboBoxModel<>(MODS);
            modModel.insertElementAt(null, 0);
            this.MODCOMBO.setModel(modModel);
            this.MODCOMBO.setSelectedIndex(0);
            this.MODPANEL.setVisible(true);        
        }, () -> hideModPanel());
    }
    
    private void hideModPanel() {
        this.MODCOMBO.setModel(new DefaultComboBoxModel<>());
        this.MODPANEL.setVisible(false);
    }
    
    /**
     * Regenerates the treeview if the underlying model has changed.
     *
     */
    void refreshTree() {
        if (null == this.save) {
            return;
        }

        this.TREE.getModel().refresh();
        this.TREE.updateUI();
    }

    /**
     * Makes the <code>ProgressIndicator</code> component available to subtasks.
     *
     * @return
     */
    ProgressIndicator createProgressIndicator(String title, ProgressModel model) {
        return ProgressIndicator.create(this.PROGRESSPANEL, title, model);
    }

    /**
     * Makes the <code>ProgressIndicator</code> component available to subtasks.
     *
     * @return
     */
    ProgressIndicator createProgressIndicator(String title) {
        return createProgressIndicator(title, new ProgressModel(1));
    }

    /**
     * Clears the <code>ESS</code>.
     */
    void clearESS() {
        this.MI_SAVE.setEnabled(false);
        this.MI_EXPORTPLUGINS.setEnabled(false);
        this.MI_REMOVEUNATTACHED.setEnabled(false);
        this.MI_REMOVEUNDEFINED.setEnabled(false);
        this.MI_RESETHAVOK.setEnabled(false);
        this.MI_CLEANSEFORMLISTS.setEnabled(false);
        this.MI_REMOVENONEXISTENT.setEnabled(false);
        this.MI_LOOKUPID.setEnabled(false);
        this.MI_LOOKUPBASE.setEnabled(false);
        this.MI_LOADESPS.setEnabled(false);
        this.PLUGINCOMBO.setModel(new DefaultComboBoxModel<>());

        this.save = null;
        this.resetTitle(null);
        this.clearContextInformation();

        final String TITLE = String.format("ReSaver %s: (сейв не загружен)", AboutDialog.getVersion());
        this.setTitle(TITLE);
    }

    
    /**
     * Sets the <code>ESS</code> containing the savefile to display.
     * @param result The results of the opening operation.
     */
    void setESSResult(Opener.Result result) {
        Objects.requireNonNull(result);
        this.openerResult = Optional.of(result);
        this.setESS(result.RESULT.SAVE_FILE, result.ESS,  result.RESULT.MODEL, result.WORRIER.shouldDisableSaving());
    }
    
    /**
     * Sets the <code>ESS</code> containing the papyrus section to display.
     *
     * @param savefile The file that contains the <code>ESS</code>.
     * @param newSave The new <code>ESS</code>.
     * @param model The <code>FilterTreeModel</code>.
     * @param disableSaving A flag indicating that saving should be disabled.
     *
     */
    private void setESS(Path savefile, ESS newSave, FilterTreeModel model, boolean disableSaving) {
        Objects.requireNonNull(savefile);
        Objects.requireNonNull(newSave);

        LOG.info("================");
        LOG.info("setESS");
        TIMER.restart();

        // If the game is Skyrim Legendary, and the string table bug was 
        // detected, disable the save menu command.
        if (disableSaving) {
            this.MI_SAVE.setEnabled(false);
            this.MI_SAVEAS.setEnabled(false);
        } else {
            this.MI_SAVE.setEnabled(true);
            this.MI_SAVEAS.setEnabled(true);
        }

        // Enable editing functions.
        this.MI_EXPORTPLUGINS.setEnabled(true);
        this.MI_REMOVEUNATTACHED.setEnabled(true);
        this.MI_REMOVEUNDEFINED.setEnabled(true);
        this.MI_RESETHAVOK.setEnabled(true);
        this.MI_CLEANSEFORMLISTS.setEnabled(true);
        this.MI_REMOVENONEXISTENT.setEnabled(true);
        this.MI_LOADESPS.setEnabled(true);

        // Clear the context info box.
        this.clearContextInformation();

        // Set the save field.
        this.save = newSave;

        // Set up the Plugins combobox.
        final java.util.List<Plugin> PLUGINS = new java.util.ArrayList<>(newSave.getPluginInfo().getAllPlugins());
        PLUGINS.sort(Plugin::compareTo);
        PLUGINS.add(0, null);
        DefaultComboBoxModel<Plugin> pluginModel = new DefaultComboBoxModel<>(PLUGINS.toArray(new Plugin[0]));

        // If a plugin was previously selected, attempt to re-select it.
        if (null != this.PLUGINCOMBO.getSelectedItem() && this.PLUGINCOMBO.getSelectedItem() instanceof Plugin) {
            final Plugin PREV = (Plugin) this.PLUGINCOMBO.getSelectedItem();
            this.PLUGINCOMBO.setModel(pluginModel);
            this.PLUGINCOMBO.setSelectedItem(PREV);
        } else {
            this.PLUGINCOMBO.setModel(pluginModel);
            this.PLUGINCOMBO.setSelectedIndex(0);
        }

        // Rebuild the tree.
        this.TREE.setESS(newSave, model, this.filter);
        this.refreshTree();

        TreePath path = model.getPath(model.getRoot());
        this.TREE.setSelectionPath(path);

        this.resetTitle(savefile);

        TIMER.stop();
        LOG.info(String.format("Treeview initialized, took %s.", TIMER.getFormattedTime()));
    }

    /**
     * Updates the setFilter.
     *
     * @param model The model to which the filters should be applied.
     */
    private boolean createFilter(FilterTreeModel model) {
        ProgressIndicator PROGRESS = createProgressIndicator("Creating filters");
        
        try {
            LOG.info("Creating filters.");
            final Mod MOD = this.MODCOMBO.getItemAt(this.MODCOMBO.getSelectedIndex());
            final Plugin PLUGIN = (Plugin) this.PLUGINCOMBO.getSelectedItem();
            final String TXT = this.FILTERFIELD.getText();

            Predicate<Node> mainfilter = null;
            if (this.save != null) {
                LOG.info("Creating FilterFactory.");
                FilterFactory factory = new FilterFactory(this.save, this.analysis);

                LOG.info("Adding filters.");
                if (this.MI_SHOWUNDEFINED.isSelected()) factory.addUndefinedSubfilter();
                if (this.MI_SHOWUNATTACHED.isSelected()) factory.addUnattachedSubfilter();
                if (this.MI_SHOWNOPARENTS.isSelected()) factory.addNoParentSubfilter();
                if (this.MI_SHOWMISSINGPARENTS.isSelected()) factory.addMissingParentSubfilter();
                if (this.MI_SHOWNULLREFS.isSelected()) factory.addNullRefSubfilter();
                if (this.MI_SHOWMEMBERLESS.isSelected()) factory.addMemberlessSubfilter();
                if (this.MI_SHOWCANARIES.isSelected()) factory.addCanarySubfilter();
                if (this.MI_SHOWNONEXISTENTCREATED.isSelected()) factory.addNonexistentSubfilter();
                if (this.MI_SHOWLONGSTRINGS.isSelected()) factory.addLongStringSubfilter();
                if (this.MI_SHOWDELETED.isSelected()) factory.addDeletedSubfilter();
                if (this.MI_SHOWEMPTY.isSelected()) factory.addVoidSubfilter();
                if (this.MI_SHOWSCRIPTATTACHED.isSelected()) factory.addHasScriptFilter();

                if (this.MI_SHOWPARSED1.isSelected()) factory.addUnparsedFilter(ParseLevel.PARSED);
                if (this.MI_SHOWPARSED2.isSelected()) factory.addUnparsedFilter(ParseLevel.PARTIAL);
                if (this.MI_SHOWPARSED3.isSelected()) factory.addUnparsedFilter(ParseLevel.UNPARSED);

                Duad<Integer> changeFilter = this.MI_CHANGEFILTER.getValue();
                Duad<Integer> changeFormFilter = this.MI_CHANGEFORMFILTER.getValue();
                String fieldCodes = this.MI_CHANGEFORMCONTENTFILTER.getValue();

                if (changeFilter != null) factory.addChangeFlagFilter(changeFilter.A, changeFilter.B);
                if (changeFormFilter != null) factory.addChangeFormFlagFilter(changeFormFilter.A, changeFormFilter.B);
                if (fieldCodes != null && !fieldCodes.chars().allMatch(Character::isWhitespace)) factory.addChangeFormContentFilter(fieldCodes);

                if (MOD != null) factory.addModFilter(MOD);
                if (PLUGIN != null) factory.addPluginFilter(PLUGIN);
                if (!TXT.isEmpty()) factory.addRegexFilter(TXT);

                mainfilter = factory.generate();
            }

            if (null == mainfilter) {
                LOG.warning("No filters generated.");
                this.filter = null;
                model.removeFilter();
                return true;
            } else {
                LOG.fine("Applying filters to model.");
                this.filter = mainfilter;
                model.setFilter(this.filter);
                return true;
            }                       
        } catch (Throwable ex) {
            LOG.log(Level.WARNING, "Ошибка создания фильтров.", ex);
            throw ex;
        } finally {
            TIMER.stop();
            PROGRESS.stop();
            LOG.info(String.format("Done creating, took %s.", TIMER.getFormattedTime()));
        }
    }

    /**
     * Updates the setFilter.
     *
     * @param clear A flag indicating to clear the filters instead of reading
     * the setFilter settings.
     */
    private void updateFilters(boolean clear) {
        try {
            PREFS.put("settings.regex", this.FILTERFIELD.getText());
            PREFS.put("settings.cfc_filter", this.MI_CHANGEFORMCONTENTFILTER.getValue());

            final ProgressModel MODEL = new ProgressModel(10);
            final ProgressIndicator PROGRESS = createProgressIndicator("Updating", MODEL);

            SwingUtilities.invokeLater(() -> {
                try {
                    TIMER.restart();
                    LOG.info("Updating filters.");

                    TreePath path = this.TREE.getSelectionPath();
                    if (clear) {
                        this.MI_SHOWPARSED0.setSelected(true);
                        this.MI_SHOWNONEXISTENTCREATED.setSelected(false);
                        this.MI_SHOWNULLREFS.setSelected(false);
                        this.MI_SHOWUNDEFINED.setSelected(false);
                        this.MI_SHOWUNATTACHED.setSelected(false);
                        this.MI_SHOWMISSINGPARENTS.setSelected(false);
                        this.MI_SHOWNOPARENTS.setSelected(false);
                        this.MI_SHOWMEMBERLESS.setSelected(false);
                        this.MI_SHOWCANARIES.setSelected(false);
                        this.MI_SHOWLONGSTRINGS.setSelected(false);
                        this.MI_SHOWDELETED.setSelected(false);
                        this.MI_SHOWEMPTY.setSelected(false);
                        this.FILTERFIELD.setText("");
                        this.MODCOMBO.setSelectedItem(null);
                        this.PLUGINCOMBO.setSelectedItem(null);
                        this.MI_CHANGEFILTER.setValue(null);
                        this.MI_CHANGEFORMFILTER.setValue(null);
                        this.MI_CHANGEFORMCONTENTFILTER.setValue("");
                    }

                    MODEL.setValue(2);

                    boolean result = this.createFilter(this.TREE.getModel());
                    if (!result || this.save == null) {
                        return;
                    }

                    MODEL.setValue(5);
                    this.refreshTree();
                    MODEL.setValue(9);

                    if (null != path) {
                        LOG.info(String.format("Updating filter: restoring path = %s", path.toString()));

                        if (path.getLastPathComponent() == null) {
                            this.TREE.clearSelection();
                            this.clearContextInformation();
                        } else {
                            this.TREE.setSelectionPath(path);
                            this.TREE.scrollPathToVisible(path);
                        }
                    }
                    MODEL.setValue(10);

                } catch (Throwable ex) {
                    LOG.log(Level.WARNING, "Ошибка обновления фильтров.", ex);
                } finally {
                    TIMER.stop();
                    PROGRESS.stop();
                    LOG.info(String.format("Filters updated, took %s.", TIMER.getFormattedTime()));
                }
            });
        } catch (Throwable ex) {
            LOG.log(Level.WARNING, "Ошибка обновления фильтров.", ex);
        }
    }

    /**
     * Exits the application immediately.
     */
    void exit() {
        javax.swing.SwingUtilities.invokeLater(() -> {
            saveWindowPosition();
            try {
                PREFS.flush();
            } catch (BackingStoreException ex) {
                LOG.log(Level.WARNING, "Ошибка сохранения настроек.", ex);
            }
            this.FILTERFIELD.terminate();
            this.LBL_MEMORY.terminate();
            this.setVisible(false);
            this.dispose();
            this.terminateJavaFX();
        });
    }

    /**
     * Exits the application after checking if the user wishes to save.
     */
    void exitWithPrompt() {
        if (null != this.save && this.modified) {
            int result = JOptionPane.showConfirmDialog(this, "Сохранить текущий файл сначала?", "Сначала сохранить?", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

            switch (result) {
                case JOptionPane.CANCEL_OPTION:
                    return;

                case JOptionPane.YES_OPTION:
                    this.save(false, () -> this.exit());
                    break;

                case JOptionPane.NO_OPTION:
                    this.exit();
                    break;
            }
        } else {
            this.exit();
        }
    }

    /**
     * Saves the currently loaded save, if any.
     *
     * @param promptForFile A flag indicating the the user should be asked what
     * filename to use.
     * @param doAfter A task to run after the save is complete.
     */
    private void save(boolean promptForFile, Runnable doAfter) {
        if (null == this.save) {
            return;
        }

        try {
            final FutureTask<Path> PROMPT = new FutureTask<>(() -> {
                Path newSaveFile = promptForFile
                        ? Configurator.selectNewSaveFile(this, this.save.getHeader().GAME)
                        : Configurator.confirmSaveFile(this, this.save.getHeader().GAME, this.save.getOriginalFile());

                if (Configurator.validWrite(newSaveFile)) {
                    return newSaveFile;
                }
                return null;
            });

            final ModalProgressDialog MODAL = new ModalProgressDialog(this, "Выбор файла", PROMPT);
            MODAL.setVisible(true);
            final Path SAVEFILE = PROMPT.get();

            if (!Configurator.validWrite(SAVEFILE)) {
                return;
            }

            final Saver SAVER = new Saver(this, SAVEFILE, this.save, doAfter);
            SAVER.execute();

        } catch (InterruptedException | ExecutionException ex) {
            LOG.log(Level.SEVERE, "Ошибка при сохранении.", ex);
        }
    }

    /**
     * Starts a batch cleaning operation.
     */
    private void batchClean() {
        if (null == this.save) {
            return;
        }

        final BatchCleaner CLEANER = new BatchCleaner(this, this.save);
        CLEANER.execute();
    }

    /**
     * Opens a savefile, preceded with a prompt to save the current one.
     *
     */
    void openWithPrompt() {
        if (null == this.save || !this.modified) {
            this.open();

        } else {
            int result = JOptionPane.showConfirmDialog(this, "Сохранить текущий файл сначала?", "Сначала сохранить?", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            switch (result) {
                case JOptionPane.YES_OPTION:
                    this.save(false, () -> this.open());
                    break;

                case JOptionPane.NO_OPTION:
                    this.open();
                    break;

                case JOptionPane.CANCEL_OPTION:
                default:
                    break;
            }
        }
    }

    /**
     * Opens a save file.
     *
     * @param parse Whether to automatically parse.
     */
    void open() {
        final Path SAVEFILE = Configurator.choosePathModal(this,
                null,
                () -> Configurator.selectSaveFile(this),
                path -> Configurator.validateSavegame(path),
                true);

        if (SAVEFILE != null) {
            open(SAVEFILE, PREFS.getBoolean("settings.alwaysParsePlugins", true));
        }
    }

    void setSorting(SortingMethod method) {
        if (method == null) return;
        
        SortingMethod previous = getSorting();
        
        if (previous != method) {
            PREFS.put("settings.sort", method.toString());
            this.TREE.setModel(ModelBuilder.createModel(new ProgressModel(), method, this.save));
        }
    }
        
    SortingMethod getSorting() {
        SortingMethod sort = SortingMethod.ALPHA;
        try {
            String val = PREFS.get("settings.sort", "ALPHA");
            sort = SortingMethod.valueOf(val);
        } catch(RuntimeException ex) {
        }
        return sort;
    }
    
    /**
     * Opens a save file.
     *
     * @param path The savefile or script to read.
     * @param parse
     *
     */
    void open(Path path, boolean parse) {
        if (Configurator.validateSavegame(path)) {
            if (this.scanner != null) {
                this.setScanning(false);
                this.scanner.cancel(true);
                this.scanner = null;
            }

            final Runnable DOAFTER = () -> {
                updateFilters(false);
                if (parse) {                    
                    scanESPs(false);
                }                
            };
            
            final Opener OPENER = new Opener(this, path, getSorting(), openerResult, DOAFTER);
            OPENER.execute();

        } else if (Mod.GLOB_SCRIPT.matches(path)) {
            try {
                final PexFile SCRIPT = PexFile.readScript(path);
                final java.util.List<String> SOURCE = new java.util.LinkedList<>();
                SCRIPT.disassemble(SOURCE, AssemblyLevel.FULL);
                final TextDialog TEXT = new TextDialog(SOURCE.stream().collect(Collectors.joining("<br/>", "<pre>", "</pre>")));
                JOptionPane.showMessageDialog(this, TEXT, path.getFileName().toString(), JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException | RuntimeException ex) {
                LOG.log(Level.WARNING, "Ошибка при декомпиляции скрипта, брошенного мышью.", ex);
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Ошибка декомпиляции", JOptionPane.ERROR_MESSAGE);
            }

        } else if (Configurator.GLOB_INI.matches(path)) {
            Configurator.storeMO2Ini(path);
        }
    }

    /**
     * Scans ESPs for contextual information.
     *
     * @param interactive A flag indicating whether to prompt the user.
     */
    public void scanESPs(boolean interactive) {
        if (this.save == null) {
            return;
        }

        if (this.scanner != null) {
            this.setScanning(false);
            this.scanner.cancel(true);
            this.scanner = null;
        }
        
        this.analysis.filter(an -> an.PLUGINS.equals(this.save.getPluginInfo())).ifPresentOrElse(this::setAnalysis, () -> {
            final Game GAME = this.save.getHeader().GAME;

            final Path GAME_DIR = Configurator.choosePathModal(this,
                    () -> Configurator.getGameDirectory(GAME),
                    () -> Configurator.selectGameDirectory(SaveWindow.this, GAME),
                    path -> Configurator.validateGameDirectory(GAME, path),
                    interactive);

            final Path MO2_INI = this.MI_USEMO2.isSelected()
                    ? Configurator.choosePathModal(this,
                            () -> Configurator.getMO2Ini(GAME),
                            () -> Configurator.selectMO2Ini(this, GAME),
                            path -> Configurator.validateMO2Ini(path),
                            interactive)
                    : null;

            if (GAME_DIR != null) {
                this.scanner = new Scanner(this, this.save, GAME_DIR, MO2_INI, 
                    this::onScanComplete, 
                    this::onScanProgress);

                this.scanner.addPropertyChangeListener(evt -> {
                    try {
                        if (scanner.isDone()) {
                            Analysis newAnalysis = scanner.get();
                            if (newAnalysis == null) {
                                this.clearAnalysis();
                            } else {
                                this.setAnalysis(newAnalysis);
                            }
                        }
                    } catch (InterruptedException|ExecutionException ex) {
                        LOG.severe("Возникла проблема при получении анализа.");
                        JOptionPane.showMessageDialog(this, "Возникла проблема при получении анализа.", "ERROR", JOptionPane.ERROR_MESSAGE);
                    }
                });

                this.scanner.execute();
                this.setScanning(true);
            }                        
        });
    }

    private void onScanProgress(String msg) {
        this.LBL_SCANNING.setText(msg);
    }

    
    /**
     * Called when scan-esps is complete.
     */
    private void onScanComplete() {
        this.setScanning(false);
        this.updateFilters(false);
    }
    
    
    /**
     * Display the settings dialogbox.
     */
    private void showSettings() {
        Game currentGame = (null == this.save ? null : this.save.getHeader().GAME);
        ReSaver_RenewedSettings settings = new ReSaver_RenewedSettings(this, currentGame);
        settings.pack();
        settings.setVisible(true);
    }

    /**
     * Exports a list of plugins.
     */
    private void exportPlugins() {
        final Path EXPORT = Configurator.choosePathModal(this,
                null,
                () -> Configurator.selectPluginsExport(this, this.save.getOriginalFile()),
                path -> Configurator.validWrite(path),
                true);

        if (null == EXPORT) {
            return;
        }

        try (BufferedWriter out = Files.newBufferedWriter(EXPORT)) {
            out.write("# Этот файл создан программой ReSaver.\n");
            for (Plugin plugin : this.save.getPluginInfo().getAllPlugins()) {
                out.write(plugin.NAME);
                out.write('\n');
            }

            final String MSG = String.format("Список плагинов экспортирован.");
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, "Успешно", JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException ex) {
            final String MSG = String.format("Ошибка при записи файла \"%s\".\n%s", EXPORT.getFileName(), ex.getMessage());
            LOG.log(Level.SEVERE, "Ошибка при экспорте списка плагинов.", ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, "Ошибка записи", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Prompts the user for a name, and finds the corresponding ID.
     */
    private void lookupID() {
        final String MSG = "Введите имя объекта или NPC:";
        final String TITLE = "Введите имя";

        String searchTerm = JOptionPane.showInputDialog(this, MSG, TITLE, JOptionPane.QUESTION_MESSAGE);
        if (null == searchTerm || searchTerm.trim().isEmpty()) {
            return;
        }

        analysis.map(an -> an.find(searchTerm)).ifPresent(matches -> {
            if (matches.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Совпадения не найдены.", "Нет совпадений", JOptionPane.ERROR_MESSAGE);
            } else {
                final StringBuilder BUF = new StringBuilder();
                BUF.append("Найдены следующие совпадения:\n\n");

                matches.forEach(id -> {
                    BUF.append(String.format("%08x", id));

                    int pluginIndex = id >>> 24;
                    final java.util.List<Plugin> PLUGINS = this.save.getPluginInfo().getFullPlugins();

                    if (0 <= pluginIndex && pluginIndex < PLUGINS.size()) {
                        final Plugin PLUGIN = PLUGINS.get(pluginIndex);
                        BUF.append(" (").append(PLUGIN).append(")");
                    }
                    BUF.append('\n');
                });

                JOptionPane.showMessageDialog(this, BUF.toString(), "Совпадения", JOptionPane.INFORMATION_MESSAGE);
                System.out.println(matches);
            }
        });
    }

    /**
     * Prompts the user for the name or ID of a reference, and finds the id/name
     * of the base object.
     */
    private void lookupBase() {

    }

    private void showDataAnalyzer(ByteBuffer data) {
        DataAnalyzer.showDataAnalyzer(this, data, this.save);
    }

    private void compareTo() {
        if (this.save == null) {
            return;
        }

        final Path otherPath = Configurator.choosePathModal(this,
                null,
                () -> Configurator.selectSaveFile(this),
                path -> Configurator.validateSavegame(path),
                true);

        if (null == otherPath) {
            return;
        }

        try {
            final ESS.Result RESULT = ESS.readESS(otherPath, new ModelBuilder(new ProgressModel(1)));
            ESS.verifyIdentical(this.save, RESULT.ESS);
            JOptionPane.showMessageDialog(this, "Расхождений не обнаружено.", "Совпадение", JOptionPane.INFORMATION_MESSAGE);

        } catch (RuntimeException | IOException | ElementException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Расхождение", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Removes unattached script instances (instances with no valid Ref).
     *
     */
    private void cleanUnattached() {
        try {
            if (null == this.save) {
                return;
            }

            LOG.info("Очистка неподключённых экземпляров.");

            Papyrus papyrus = this.save.getPapyrus();
            final Set<PapyrusElement> REMOVED = papyrus.removeUnattachedInstances();

            if (!REMOVED.isEmpty()) {
                String msg = String.format("Удалено сиротских экземпляров скриптов: %d.", REMOVED.size());
                LOG.info(msg);
                JOptionPane.showMessageDialog(this, msg, "Очищено", JOptionPane.INFORMATION_MESSAGE);

                this.deleteNodesFor(REMOVED);
                this.setModified();

            } else {
                JOptionPane.showMessageDialog(this, "Неподключённые экземпляры не найдены.", "Ничего не найдено", JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (HeadlessException ex) {
            final String MSG = "Ошибка при очистке неподключённых скриптов.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Remove undefined script instances (instances with no Script).
     */
    private void cleanUndefined() {
        try {
            if (null == this.save) {
                return;
            }

            LOG.info("Очистка неопределённых элементов.");
            Papyrus papyrus = this.save.getPapyrus();
            Set<PapyrusElement> REMOVED = papyrus.removeUndefinedElements();
            Set<ActiveScript> TERMINATED = papyrus.terminateUndefinedThreads();

            if (!REMOVED.isEmpty() || !TERMINATED.isEmpty()) {
                final StringBuilder BUF = new StringBuilder();
                if (!REMOVED.isEmpty()) {
                    this.deleteNodesFor(REMOVED);
                    this.setModified();
                    BUF.append("Удалено ").append(REMOVED.size()).append(" неопределённых элементов.");
                }
                if (!TERMINATED.isEmpty()) {
                    BUF.append("Завершено ").append(TERMINATED).append(" неопределённых потоков.");
                }

                final String MSG = BUF.toString();
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, "Очищено", JOptionPane.INFORMATION_MESSAGE);

            } else {
                JOptionPane.showMessageDialog(this, "Неопределённые элементы не найдены.", "Ничего не найдено", JOptionPane.INFORMATION_MESSAGE);
            }

        } catch (HeadlessException ex) {
            final String MSG = "Ошибка при очистке неопределённых элементов.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     *
     */
    private void resetHavok() {
         if (null == this.save) {
            return;
         }

        int confirm = JOptionPane.showConfirmDialog(this, "Вы уверены? Редактирование ChangeForms опасно.", "Вы уверены?", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }
        
        try {
            final FutureTask<int[]> STRIP = new FutureTask<>(() -> {
                LOG.info("Удаляю данные Havok.");
                return this.save.resetHavok(this.analysis);
            });

            final ModalProgressDialog MODAL = new ModalProgressDialog(this, "Удаление...", STRIP);
            MODAL.setVisible(true);
            int[] results = STRIP.get();

            this.setModified();
            final String MSG = String.format("Успешно для ChangeForms: %d.\nСо сбоем для ChangeForms: %d.", results[0], results[1]);
            final String TITLE = "Результат сброса Havok";
            LOG.info(MSG);
            JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);

            this.refreshTree();

        } catch (InterruptedException | ExecutionException | HeadlessException ex) {
            final String MSG = "Ошибка при удалении данных Havok.";
            final String TITLE = "Ошибка удаления";
            LOG.log(Level.SEVERE, MSG, ex);
            ex.printStackTrace(System.err);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     *
     */
    private void cleanseFormLists() {
        if (null == this.save) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this, "Вы уверены? Редактирование ChangeForms опасно.", "Вы уверены?", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            final FutureTask<int[]> CLEANSE = new FutureTask<>(() -> {
                LOG.info("Очистка формлистов.");
                return this.save.cleanseFormLists(this.analysis);
            });

            final ModalProgressDialog MODAL = new ModalProgressDialog(this, "Очистка...", CLEANSE);
            MODAL.setVisible(true);
            int[] results = CLEANSE.get();
            
            if (results[0] == 0) {
                final String MSG = "Ни в одном формлисте пустых ссылок не найдено.";
                final String TITLE = "Пустые ссылки не найдены.";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);

            } else {
                this.setModified();
                final String MSG = String.format("Удалено пустых ссылок: %d (в %d формлистах).", results[0], results[1]);
                final String TITLE = "Пустые ссылки удалены.";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
            }

            this.refreshTree();

        } catch (InterruptedException | ExecutionException | HeadlessException ex) {
            final String MSG = "Ошибка при очистке формлистов.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            ex.printStackTrace(System.err);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     *
     * @param flst
     */
    private void cleanseFormList(ChangeFormFLST flst) {
        try {
            if (null == this.save) {
                return;
            }

            LOG.info(String.format("Очистка формлиста %s.", flst));
            int result = flst.cleanse();

            if (result == 0) {
                final String MSG = "Пустые ссылки не найдены.";
                final String TITLE = "Пустые ссылки не найдены.";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
            } else {
                this.setModified();
                final String MSG = String.format("Удалено пустых ссылок: %d.", result);
                final String TITLE = "Пустые ссылки удалены.";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
            }

            this.refreshTree();

        } catch (HeadlessException ex) {
            final String MSG = "Ошибка при очистке формлистов.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Removes script instances attached to nonexistent created forms.
     */
    private void cleanNonexistent() {
        // Check with the user first. This operation can mess up mods.
        final String WARN = "Эта операция очистки может привести к тому, что некоторые моды перестанут работать. Вы уверены, что хотите это сделать?";
        final String WARN_TITLE = "Предупреждение";
        int confirm = JOptionPane.showConfirmDialog(this, WARN, WARN_TITLE, JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            if (null == this.save) {
                return;
            }

            LOG.info(String.format("Удаление несуществующих созданных форм."));
            final Set<PapyrusElement> REMOVED = this.save.removeNonexistentCreated();

            if (!REMOVED.isEmpty()) {
                final String MSG = "Скрипты на несуществующих созданных формах не найдены.";
                final String TITLE = "Несуществующих созданных форм нет";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
            } else {
                this.setModified();
                final String MSG = String.format("Удалено экземпляров: %d.", REMOVED.size());
                final String TITLE = "Экземпляры удалены.";
                LOG.info(MSG);
                JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
            }

            this.deleteNodesFor(REMOVED);

        } catch (HeadlessException ex) {
            final String MSG = "Ошибка при очистке несуществующих созданных форм.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }

    }

    /**
     * Save minor settings like window position, size, and state.
     */
    private void saveWindowPosition() {
        PREFS.putInt("settings.extendedState", this.getExtendedState());

        if (this.getExtendedState() == JFrame.NORMAL) {
            PREFS.putInt("settings.windowWidth", this.getSize().width);
            PREFS.putInt("settings.windowHeight", this.getSize().height);
            PREFS.putInt("settings.windowX", this.getLocation().x);
            PREFS.putInt("settings.windowY", this.getLocation().y);
            PREFS.putInt("settings.mainDivider", this.MAINSPLITTER.getDividerLocation());
            PREFS.putInt("settings.rightDivider", this.RIGHTSPLITTER.getDividerLocation());
            System.out.printf("Pos = %s\n", this.getLocation());
            System.out.printf("Size = %s\n", this.getSize());
            System.out.printf("Dividers = %d,%d\n", this.MAINSPLITTER.getDividerLocation(), this.RIGHTSPLITTER.getDividerLocation());
        } else {
            PREFS.putInt("settings.mainDividerMax", this.MAINSPLITTER.getDividerLocation());
            PREFS.putInt("settings.rightDividerMax", this.RIGHTSPLITTER.getDividerLocation());
        }
    }

    /**
     * Loads minor settings like window position, size, and state.
     */
    private void restoreWindowPosition() {
        if (this.getExtendedState() == JFrame.NORMAL) {
            java.awt.Point pos = this.getLocation();
            java.awt.Dimension size = this.getSize();
            int x = PREFS.getInt("settings.windowX", pos.x);
            int y = PREFS.getInt("settings.windowY", pos.y);
            int width = PREFS.getInt("settings.windowWidth", size.width);
            int height = PREFS.getInt("settings.windowHeight", size.height);
            this.setLocation(x, y);
            this.setSize(width, height);

            float mainDividerLocation = this.MAINSPLITTER.getDividerLocation();
            mainDividerLocation = PREFS.getFloat("settings.mainDivider", mainDividerLocation);
            float rightDividerLocation = this.RIGHTSPLITTER.getDividerLocation();
            rightDividerLocation = PREFS.getFloat("settings.rightDivider", rightDividerLocation);
            this.MAINSPLITTER.setDividerLocation(Math.max(0.1, Math.min(0.9, mainDividerLocation)));
            this.RIGHTSPLITTER.setDividerLocation(Math.max(0.1, Math.min(0.9, rightDividerLocation)));

        } else {
            float mainDividerLocation = this.MAINSPLITTER.getDividerLocation();
            mainDividerLocation = PREFS.getFloat("settings.mainDividerMax", mainDividerLocation);
            float rightDividerLocation = this.RIGHTSPLITTER.getDividerLocation();
            rightDividerLocation = PREFS.getFloat("settings.rightDividerMax", rightDividerLocation);
            this.MAINSPLITTER.setDividerLocation(Math.max(0.1, Math.min(0.9, mainDividerLocation)));
            this.RIGHTSPLITTER.setDividerLocation(Math.max(0.1, Math.min(0.9, rightDividerLocation)));
        }
    }

    /**
     *
     */
    private void kill() {
        try {
            final java.util.List<Element> ELEMENTS = this.TREE.getModel().getElements();
            this.setModified();

            final Set<Element> REMOVED = this.save.removeElements(ELEMENTS);
            this.deleteNodesFor(REMOVED);

        } catch (Exception ex) {
            final String MSG = "Ошибка при очистке формлистов.";
            final String TITLE = "Ошибка очистки";
            LOG.log(Level.SEVERE, MSG, ex);
            JOptionPane.showMessageDialog(SaveWindow.this, MSG, TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     *
     */
    Watcher getWatcher() {
        return this.WATCHER;
    }

    /**
     * Start or stop the watcher service.
     *
     * @param enabled Indicates whether to start or terminate the watcher.
     */
    public void setWatching(boolean enabled) {
        if (enabled && !this.WATCHER.isRunning()) {
            this.LBL_WATCHING.setVisible(true);            
            
            Stream<Path> dirs1 = Game.VALUES.stream()
                    .map(game -> Configurator.getSaveDirectory(game))
                    .filter(Objects::nonNull);

            Stream<Path> dirs2 = Game.VALUES.stream()
                    .map(game -> Configurator.getMO2Profile(Configurator.getMO2Ini(game)))
                    .filter(Objects::nonNull)
                    .map(p -> p.resolve("saves"));

            SortedSet<Path> watchDirectories = Stream.concat(dirs1, dirs2)
                .filter(Files::exists)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
            
            this.WATCHER.start(watchDirectories.toArray(new Path[0]));

            this.LBL_WATCHING.setToolTipText(watchDirectories.stream()
                    .map(p -> p.toString())
                    .collect(Collectors.joining("<li>", "<html>Отслеживание:<ul><li>", "</ul></html>")));
            
            if (!this.MI_WATCHSAVES.isSelected()) {
                this.MI_WATCHSAVES.setSelected(true);
            }
        } else if (!enabled && this.WATCHER.isRunning()) {
            this.LBL_WATCHING.setVisible(false);
            this.WATCHER.stop();
            if (this.MI_WATCHSAVES.isSelected()) {
                this.MI_WATCHSAVES.setSelected(false);
            }
        }
    }

    /**
     * Begin the watcher service.
     */
    void setScanning(boolean enabled) {
        if (enabled && this.scanner != null && !this.scanner.isDone()) {
            this.LBL_SCANNING.setVisible(true);
            this.MI_LOADESPS.setEnabled(false);
        } else {
            this.LBL_SCANNING.setVisible(false);
            this.MI_LOADESPS.setEnabled(this.save != null && this.analysis.isPresent());
        }
    }

    /**
     *
     */
    private void showLog() {
        JDialog dialog = new JDialog(this, "Журнал");
        dialog.setContentPane(this.LOGWINDOW);
        dialog.setModalityType(JDialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setPreferredSize(new Dimension(600, 400));
        dialog.setLocationRelativeTo(null);
        dialog.pack();
        dialog.setVisible(true);
    }

    /**
     *
     */
    private void setChangeFlagFilter() {
        Duad<Integer> pair = this.MI_CHANGEFILTER.getValue();
        if (null == pair) {
            pair = Duad.make(0, 0);
        }

        ChangeFlagDialog dlg = new ChangeFlagDialog(this, pair.A, pair.B, (m, f) -> {
            Duad<Integer> newPair = Duad.make(m, f);
            this.MI_CHANGEFILTER.setValue(newPair);
            this.updateFilters(false);
        });

        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /**
     *
     */
    private void setChangeFormFlagFilter() {
        Duad<Integer> pair = this.MI_CHANGEFORMFILTER.getValue();
        if (null == pair) {
            pair = Duad.make(0, 0);
        }

        ChangeFlagDialog dlg = new ChangeFlagDialog(this, pair.A, pair.B, (m, f) -> {
            Duad<Integer> newPair = Duad.make(m, f);
            this.MI_CHANGEFORMFILTER.setValue(newPair);
            this.updateFilters(false);
        });

        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void setChangeFormContentFilter() {
        String result = (String)JOptionPane.showInputDialog(
               this,
               "Введите коды полей для фильтра содержимого:", 
               "Фильтр содержимого ChangeForm",            
               JOptionPane.QUESTION_MESSAGE,
               null,            
               null, 
               this.MI_CHANGEFORMCONTENTFILTER.getValue()
            );
        
            if(result != null && result.length() > 0){
                this.MI_CHANGEFORMCONTENTFILTER.setValue(result);
                this.updateFilters(false);
            }    
    }
    
    private DefinedElement findOwner(Element element) {
        if (this.save == null) {
            return null;
        } else {
            return this.save
                    .getPapyrus()
                    .getContext()
                    .findReferees(element)
                    .stream()
                    .findFirst()
                    .orElse(null);
        }
    }
    
    
    /**
     * Selects an <code>Element</code> in the <code>FilterTree</code>.
     *
     * @param element The <code>Element</code> to find.
     * @param index The index of the data table for <code>Element</code> to
     * select.
     */
    void findElement(Element element, int index) {
        Objects.requireNonNull(element);
        this.findElement(element);
        this.TABLE.scrollSelectionToVisible(index);
    }

    /**
     * Selects an <code>Element</code> in the <code>FilterTree</code>.
     *
     * @param element The <code>Element</code> to select.
     *
     */
    void findElement(Element element) {
        Objects.requireNonNull(element);
        if (null == element) {
            return;
        }

        TreePath path = this.TREE.findPath(element);

        if (null == path) {
            JOptionPane.showMessageDialog(this, "Элемент не найден.", "Не найдено", JOptionPane.ERROR_MESSAGE);
            return;
        }

        this.TREE.updateUI();
        this.TREE.scrollPathToVisible(path);
        this.TREE.setSelectionPath(path);
    }

    /**
     * Deletes plugins' script instances and forms.
     *
     * @param plugins The list of plugins to purge.
     * @param purgeForms A flag indicating to purge changeforms.
     * @param purgeScripts A flag indicating to purge script instances.
     * @return The count of instances and changeforms removed.
     */
    private void purgePlugins(Collection<Plugin> plugins, boolean purgeScripts, boolean purgeForms) {
        Objects.requireNonNull(plugins);
        final int NUM_FORMS, NUM_INSTANCES;

        if (purgeScripts) {
            final java.util.Set<ScriptInstance> INSTANCES = plugins.stream()
                    .flatMap(p -> p.getInstances(this.save).stream())
                    .collect(Collectors.toSet());
            NUM_INSTANCES = INSTANCES.size();
            Set<PapyrusElement> REMOVED = this.save.getPapyrus().removeElements(INSTANCES);
            assert REMOVED.size() == NUM_INSTANCES : String.format("Удалено экземпляров: %d из %d.", REMOVED.size(), NUM_INSTANCES);

            if (!REMOVED.isEmpty()) {
                this.deleteNodesFor(REMOVED);
                this.setModified();
            }
        } else {
            NUM_INSTANCES = 0;
        }

        if (purgeForms) {
            final java.util.Set<ChangeForm> FORMS = plugins.stream()
                    .flatMap(p -> p.getChangeForms(this.save).stream())
                    .collect(Collectors.toSet());
            NUM_FORMS = FORMS.size();
            final Set<ChangeForm> REMOVED = this.save.removeChangeForms(FORMS);
            assert REMOVED.size() == NUM_FORMS : String.format("Удалено форм: %d из %d.", REMOVED.size(), NUM_FORMS);

            if (!REMOVED.isEmpty()) {
                this.deleteNodesFor(REMOVED);
                this.setModified();
            }
        } else {
            NUM_FORMS = 0;
        }

        final String TITLE = "Чистка плагинов";

        if (NUM_INSTANCES > 0 && NUM_FORMS == 0) {
            final String FORMAT = "Удалено экземпляров скриптов: %d (плагинов: %d).";
            final String MSG = String.format(FORMAT, NUM_INSTANCES, plugins.size());
            JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);

        } else if (NUM_INSTANCES == 0 && NUM_FORMS > 0) {
            final String FORMAT = "Удалено ChangeForms: %d (плагинов: %d).";
            final String MSG = String.format(FORMAT, NUM_FORMS, plugins.size());
            JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);

        } else if (NUM_INSTANCES > 0 && NUM_FORMS > 0) {
            final String FORMAT = "Удалено: экземпляров скриптов %d, ChangeForms %d (плагинов: %d).";
            final String MSG = String.format(FORMAT, NUM_INSTANCES, NUM_FORMS, plugins.size());
            JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);

        } else {
            final String MSG = "Удалять было нечего.";
            JOptionPane.showMessageDialog(this, MSG, TITLE, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Zero a thread, terminating it.
     *
     * @param threads An <code>Element</code> <code>List</code> that will be
     * terminated.
     */
    private void zeroThreads(java.util.List<ActiveScript> threads) {
        Objects.requireNonNull(threads);
        if (threads.isEmpty()) {
            return;
        }

        final String QUESTION = threads.size() > 1
                ? String.format("Вы уверены, что хотите завершить эти потоки (%d шт.)?", threads.size())
                : "Вы уверены, что хотите завершить этот поток?";
        final String TITLE = "Завершение потоков";
        int result = JOptionPane.showConfirmDialog(this, QUESTION, TITLE, JOptionPane.YES_NO_OPTION);
        if (result == JOptionPane.NO_OPTION) {
            return;
        }

        this.setModified();
        threads.forEach(t -> t.zero());
        this.refreshTree();

        final String MSG = threads.size() > 1
                ? "Поток завершён и обнулён."
                : "Потоки завершены и обнулёны.";
        LOG.info(MSG);
        JOptionPane.showMessageDialog(this, MSG, "Завершение потоков", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Deletes selected elements of the tree.
     *
     * @param elements The selections to delete.
     */
    public void deletePaths(Map<Element, Node> elements) {
        this.deleteElements(elements.keySet());
    }

    /**
     * Deletes selected elements of the tree.
     *
     * @param elements The selections to delete.
     */
    public void deleteElements(Set<Element> elements) {
        if (null == this.save || null == elements || elements.isEmpty()) {
            return;
        }

        // Save the selected row so that we can select it again after this is done.        
        final int ROW = this.TREE.getSelectionModel().getMaxSelectionRow();

        if (elements.size() == 1) {
            final Element ELEMENT = elements.iterator().next();
            final String WARNING;

            if (ESS.THREAD.test(ELEMENT)) {
                WARNING = String.format("Элемент \"%s\" — это поток Papyrus. Его удаление может сделать сейв незагружаемым. Вы уверены, что хотите продолжить?", ELEMENT.toString());
            } else if (ESS.DELETABLE.test(ELEMENT)) {
                WARNING = String.format("Вы уверены, что хотите удалить этот элемент?\n%s", ELEMENT);
            } else if (ELEMENT instanceof SuspendedStack) {
                WARNING = String.format("Элемент \"%s\" — это приостановленный стек. Его удаление может сделать сейв незагружаемым. Вы уверены, что хотите продолжить?", ELEMENT.toString());
            } else {
                return;
            }

            final String TITLE = "Предупреждение";
            int result = JOptionPane.showConfirmDialog(this, WARNING, TITLE, JOptionPane.OK_CANCEL_OPTION);
            if (result == JOptionPane.CANCEL_OPTION) {
                return;
            }

            final Set<Element> REMOVED = this.save.removeElements(elements);
            this.deleteNodesFor(REMOVED);

            if (REMOVED.containsAll(elements)) {
                final String MSG = String.format("Элемент удалён:\n%s", ELEMENT);
                JOptionPane.showMessageDialog(this, MSG, "Элемент удалён", JOptionPane.INFORMATION_MESSAGE);
                LOG.info(MSG);
            } else {
                final String MSG = String.format("Не удалось удалить элемент:\n%s", ELEMENT);
                JOptionPane.showMessageDialog(this, MSG, "Ошибка", JOptionPane.ERROR_MESSAGE);
                LOG.warning(MSG);
            }

        } else {

            final java.util.Set<Element> DELETABLE = elements.stream()
                    .filter(ESS.DELETABLE)
                    .filter(v -> !(v instanceof ActiveScript))
                    .filter(v -> !(v instanceof SuspendedStack))
                    .collect(Collectors.toSet());

            final java.util.Set<SuspendedStack> STACKS = elements.stream()
                    .filter(v -> v instanceof SuspendedStack)
                    .map(v -> (SuspendedStack) v)
                    .collect(Collectors.toSet());

            final java.util.Set<ActiveScript> THREADS = elements.stream()
                    .filter(v -> v instanceof ActiveScript)
                    .map(v -> (ActiveScript) v)
                    .collect(Collectors.toSet());

            boolean deleteStacks = false;
            if (!STACKS.isEmpty()) {
                final String WARN = "Удаление приостановленных стеков может сделать сейв незагружаемым.\nВы уверены, что хотите удалить приостановленные стеки?\nЕсли выбрать «Нет», они будут пропущены, а не удалены.";
                final String TITLE = "Предупреждение";
                int result = JOptionPane.showConfirmDialog(this, WARN, TITLE, JOptionPane.YES_NO_CANCEL_OPTION);
                if (result == JOptionPane.CANCEL_OPTION) {
                    return;
                }
                deleteStacks = (result == JOptionPane.YES_OPTION);
            }

            boolean deleteThreads = false;
            if (!THREADS.isEmpty()) {
                final String WARN = "Удаление активных скриптов может сделать сейв незагружаемым.\nВы уверены, что хотите удалить активные скрипты?\nЕсли выбрать «Нет», они будут завершены, а не удалены.";
                final String TITLE = "Предупреждение";
                int result = JOptionPane.showConfirmDialog(this, WARN, TITLE, JOptionPane.YES_NO_CANCEL_OPTION);
                if (result == JOptionPane.CANCEL_OPTION) {
                    return;
                }
                deleteThreads = (result == JOptionPane.YES_OPTION);
            }

            int count = DELETABLE.size();
            count += (deleteStacks ? STACKS.size() : 0);
            count += (deleteThreads ? THREADS.size() : 0);

            if (DELETABLE.isEmpty() && STACKS.isEmpty() && THREADS.isEmpty()) {
                return;
            }

            final String QUESTION;
            if (DELETABLE.isEmpty() && THREADS.isEmpty()) {
                return;
            } else if (count == 0 && !THREADS.isEmpty()) {
                QUESTION = String.format("Вы уверены, что хотите завершить эти активные скрипты (%d шт.)?", THREADS.size());
            } else if (deleteThreads || (count > 0 && THREADS.isEmpty())) {
                QUESTION = String.format("Вы уверены, что хотите удалить эти элементы (%d шт.) и все зависимые от них?", count);
            } else {
                QUESTION = String.format("Вы уверены, что хотите завершить эти активные скрипты (%d шт.) и удалить элементы (%d шт.) с их зависимостями?", THREADS.size(), count);
            }

            int result = JOptionPane.showConfirmDialog(this, QUESTION, "Удалить элементы", JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.NO_OPTION) {
                return;
            }

            final Set<Element> REMOVED = this.save.removeElements(DELETABLE);
            THREADS.forEach(v -> v.zero());
            if (deleteThreads) {
                REMOVED.addAll(this.save.getPapyrus().removeElements(THREADS));
            }

            if (deleteStacks) {
                REMOVED.addAll(this.save.getPapyrus().removeElements(STACKS));
            }

            this.deleteNodesFor(REMOVED);

            final StringBuilder BUF = new StringBuilder();
            BUF.append(REMOVED.size()).append(" элементов удалено.");

            if (!THREADS.isEmpty()) {
                BUF.append("\n").append(THREADS.size());
                BUF.append(deleteThreads ? " потоков завершено и удалено." : " потоков завершено.");
            }

            final String MSG = BUF.toString();
            LOG.info(MSG);
            JOptionPane.showMessageDialog(this, MSG, "Элементы удалены", JOptionPane.INFORMATION_MESSAGE);
        }

        // Select the next row.
        this.TREE.setSelectionRow(ROW);
        this.setModified();
    }

    /**
     *
     * @param newCompressionType
     */
    void setCompressionType(CompressionType newCompressionType) {
        if (this.save != null && this.save.supportsCompression() && newCompressionType != null) {
            int result = JOptionPane.showConfirmDialog(this, "Вы уверены, что хотите сменить тип сжатия?", "Подтверждение", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                this.save.getHeader().setCompression(newCompressionType);
                this.setModified();
            }
        }
    }

    /**
     * Deletes nodes from the tree.
     *
     * @param removed
     */
    public void deleteNodesFor(Set<? extends Element> removed) {
        this.TREE.getModel().deleteElements(removed);
        this.refreshTree();
    }

    /**
     * Edits an element. Currently just globalvariables.
     *
     * @param element
     */
    private void editElement(Element element) {
        if (element instanceof GlobalVariable) {
            final GlobalVariable VAR = (GlobalVariable) element;
            String response = JOptionPane.showInputDialog(this, "Введите новое значение:", VAR.getValue());
            if (null != response) {
                try {
                    float newVal = Float.parseFloat(response);
                    VAR.setValue(newVal);
                    JOptionPane.showMessageDialog(this, "Глобальная переменная обновлена.", "Успешно", JOptionPane.PLAIN_MESSAGE);

                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(this, "Неверное число.", "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    /**
     * Updates infopane data.
     *
     */
    private void updateContextInformation() {
        final TreePath PATH = this.TREE.getSelectionPath();
        if (null == PATH) {
            this.clearContextInformation();
            return;
        }

        final Object OBJ = PATH.getLastPathComponent();
        if (!(OBJ instanceof Node)) {
            this.clearContextInformation();
            return;
        }

        final Node NODE = (Node) OBJ;

        if (NODE.hasElement()) {
            this.showContextInformation(NODE.getElement());
        } else {
            this.clearContextInformation();
        }
    }

    /**
     * Clears infopane data.
     *
     */
    private void clearContextInformation() {
        this.INFOPANE.setText("");
        this.TABLE.clearTable();
    }

    /**
     *
     * @param element
     */
    private void showContextInformation(Element element) {
        this.clearContextInformation();

        if (element instanceof ESS) {
            this.RIGHTSPLITTER.setResizeWeight(1.0);
            this.RIGHTSPLITTER.setDividerLocation(1.0);
            
            String worries = openerResult.map(r -> r.WORRIER.getMessage().toString()).orElse("");
            this.INFOPANE.setText(this.save.getInfo(analysis) + worries + "<hr/>");

            try {
                final Document DOC = this.INFOPANE.getDocument();
                final int ICONWIDTH = this.INFOPANE.getWidth() * 95 / 100;
                final ImageIcon IMAGE = this.save.getHeader().getImage(ICONWIDTH);
                
                if (null != IMAGE) {
                    final Style STYLE = new StyleContext().getStyle(StyleContext.DEFAULT_STYLE);
                    final JLabel IMAGELABEL = new JLabel(this.save.getHeader().getImage(ICONWIDTH));
                    StyleConstants.setComponent(STYLE, IMAGELABEL);
                    DOC.insertString(DOC.getLength(), "Игнорируется", STYLE);
                    
                    IMAGELABEL.addMouseListener(new MouseAdapter() {
                        @Override public void mousePressed(MouseEvent e) {
                            if (e.isPopupTrigger()) doPop(e);
                        }
                        @Override public void mouseReleased(MouseEvent e) {
                            if (e.isPopupTrigger()) doPop(e);
                        }
                        private void doPop(MouseEvent e) {
                            JMenuItem copy = new JMenuItem("Копировать в буфер обмена");
                            JPopupMenu menu = new JPopupMenu();
                            menu.add(copy);
                            copy.addActionListener(ae -> ImageTransferable.CopyToClipboard(IMAGE.getImage()));
                            menu.show(e.getComponent(), e.getX(), e.getY());
                        }                        
                    });
                }
            } catch (javax.swing.text.BadLocationException ex) {
                LOG.log(Level.WARNING, "Ошибка при показе контекстной информации ESS.", ex);
            }

        } else if (element instanceof AnalyzableElement) {
            AnalyzableElement analyte = (AnalyzableElement) element;
            this.INFOPANE.setText(analyte.getInfo(analysis, save));

            if (this.TABLE.isSupported(analyte)) {
                this.RIGHTSPLITTER.setResizeWeight(0.66);
                this.RIGHTSPLITTER.setDividerLocation(0.66);
                this.TABLE.displayElement(analyte, this.save.getPapyrus().getContext());
            } else {
                this.RIGHTSPLITTER.setResizeWeight(1.0);
                this.RIGHTSPLITTER.setDividerLocation(1.0);
                this.TABLE.clearTable();
            }

        } else if (element instanceof GlobalVariable) {
            this.INFOPANE.setText(((GlobalVariable) element).getInfo(analysis, save));
            
        } else if (element instanceof GeneralElement) {
            this.INFOPANE.setText(((GeneralElement) element).getInfo(analysis, save));
        }
    }

    /**
     * Resolves a URL.
     *
     * @param event The <code>HyperLinkEvent</code> to handle.
     * @see HyperlinkListener#hyperlinkUpdate(javax.swing.event.HyperlinkEvent)
     */
    public void hyperlinkUpdate(HyperlinkEvent event) {
        if (event.getEventType() == HyperlinkEvent.EventType.ENTERED) {
            if (event.getSource() == this.INFOPANE) {
                JComponent component = (JComponent) event.getSource();
                component.setToolTipText(event.getDescription());
            }
            return;
        } else if (event.getEventType() == HyperlinkEvent.EventType.EXITED) {
            if (event.getSource() == this.INFOPANE) {
                JComponent component = (JComponent) event.getSource();
                component.setToolTipText(null);
            }
            return;
        }

        final String URL = event.getDescription();

        LOG.info(String.format("Resolving URL: %s", URL));
        final java.util.regex.Matcher MATCHER = URLPATTERN.matcher(URL);
        if (!MATCHER.find()) {
            LOG.warning(String.format("Не удалось разрешить URL: %s", URL));
            return;
        }

        final String TYPE = MATCHER.group("type");
        final String ADDRESS = MATCHER.group("address");

        Integer index1 = null;
        try {
            index1 = Integer.valueOf(MATCHER.group("target1"));
        } catch (NumberFormatException | NullPointerException ex) {
        }

        Integer index2 = null;
        try {
            index2 = Integer.valueOf(MATCHER.group("target2"));
        } catch (NumberFormatException | NullPointerException ex) {
        }

        final PapyrusContext CONTEXT = this.save.getPapyrus().getContext();

        try {
            switch (TYPE) {
                case "string":
                    int stringIndex = Integer.parseInt(ADDRESS);
                    this.findElement(CONTEXT.getTString(stringIndex));
                    break;

                case "plugin":
                    this.save.getPluginInfo().stream()
                            .filter(v -> v.NAME.equalsIgnoreCase(ADDRESS))
                            .findAny()
                            .ifPresent(plugin -> this.findElement(plugin));
                    break;

                case "refid":
                    final RefID REFID = CONTEXT.makeRefID(Integer.parseInt(ADDRESS, 16));
                    this.findElement(CONTEXT.getChangeForm(REFID));
                    break;

                case "script": {
                    final TString NAME = this.save.getPapyrus().getStringTable().resolve(ADDRESS);
                    if (index1 != null) {
                        this.findElement(CONTEXT.findScript(NAME), index1);
                    } else {
                        this.findElement(CONTEXT.findScript(NAME));
                    }
                    break;
                }
                case "struct": {
                    final TString NAME = this.save.getPapyrus().getStringTable().resolve(ADDRESS);
                    if (index1 != null) {
                        this.findElement(CONTEXT.findStruct(NAME), index1);
                    } else {
                        this.findElement(CONTEXT.findStruct(NAME));
                    }
                    break;
                }
                case "scriptinstance": {
                    final EID ID = CONTEXT.makeEID(Long.parseUnsignedLong(ADDRESS, 16));
                    if (index1 != null) {
                        this.findElement(CONTEXT.findScriptInstance(ID), index1);
                    } else {
                        this.findElement(CONTEXT.findScriptInstance(ID));
                    }
                    break;
                }
                case "structinstance": {
                    final EID ID = CONTEXT.makeEID(Long.parseUnsignedLong(ADDRESS, 16));
                    if (index1 != null) {
                        this.findElement(CONTEXT.findStructInstance(ID), index1);
                    } else {
                        this.findElement(CONTEXT.findStructInstance(ID));
                    }
                    break;
                }
                case "reference": {
                    final EID ID = CONTEXT.makeEID(Long.parseUnsignedLong(ADDRESS, 16));
                    if (index1 != null) {
                        this.findElement(CONTEXT.findReference(ID), index1);
                    } else {
                        this.findElement(CONTEXT.findReference(ID));
                    }
                    break;
                }
                case "array": {
                    final EID ID = CONTEXT.makeEID(Long.parseUnsignedLong(ADDRESS, 16));
                    if (index1 != null) {
                        this.findElement(CONTEXT.findArray(ID), index1);
                    } else {
                        this.findElement(CONTEXT.findArray(ID));
                    }
                    break;
                }
                case "thread": {
                    final EID ID = CONTEXT.makeEID32(Integer.parseUnsignedInt(ADDRESS, 16));
                    if (index1 != null) {
                        this.findElement(CONTEXT.findActiveScript(ID), index1);
                    } else {
                        this.findElement(CONTEXT.findActiveScript(ID));
                    }
                    break;
                }
                case "suspended": {
                    final EID ID = CONTEXT.makeEID32(Integer.parseUnsignedInt(ADDRESS, 16));
                    final SuspendedStack STACK = this.save.getPapyrus().getSuspendedStacks().get(ID);
                    if (index1 != null) {
                        this.findElement(STACK, index1);
                    } else {
                        this.findElement(STACK);
                    }
                    break;
                }
                case "unbind": {
                    final EID ID = CONTEXT.makeEID(Long.parseUnsignedLong(ADDRESS, 16));
                    final QueuedUnbind UNBIND = this.save.getPapyrus().getUnbinds().get(ID);
                    if (index1 != null) {
                        this.findElement(UNBIND, index1);
                    } else {
                        this.findElement(UNBIND);
                    }
                    break;
                }
                case "message": {
                    final EID ID = CONTEXT.makeEID32(Integer.parseUnsignedInt(ADDRESS, 16));
                    final FunctionMessage MESSAGE = this.save.getPapyrus().getFunctionMessages().stream()
                            .filter(v -> v.getID().equals(ID))
                            .findAny().orElse(null);
                    if (index1 != null) {
                        this.findElement(MESSAGE, index1);
                    } else {
                        this.findElement(MESSAGE);
                    }
                    break;
                }
                case "frame": {
                    final EID ID = CONTEXT.makeEID32(Integer.parseUnsignedInt(ADDRESS, 16));
                    final ActiveScript THREAD = CONTEXT.findActiveScript(ID);
                    if (THREAD != null && index1 != null) {
                        final StackFrame FRAME = THREAD.getStackFrames().get(index1);
                        if (index2 != null) {
                            this.findElement(FRAME, index2);
                        } else {
                            this.findElement(FRAME);
                        }
                    }
                    break;
                }
                case "rawform": {
                    final RefID REF = CONTEXT.makeRefID(Integer.parseUnsignedInt(ADDRESS, 16));
                    final ChangeForm FORM = CONTEXT.getChangeForm(REF);
                    this.showDataAnalyzer(FORM.getBodyData());
                }
            }
        } catch (NumberFormatException | IndexOutOfBoundsException ex) {
            LOG.warning(String.format("Неверный адрес: %s", URL));
        }
    }

    /**
     * Try to initialize JavaFX.
     *
     * @return An uncast <code>JFXPanel</code> object, or null if JavaFX could
     * not be found.
     *
     */
    Object initializeJavaFX() {
        try {
            final Class<?> CLASS_JFXPANEL = Class.forName("javafx.embed.swing.JFXPanel");
            java.lang.reflect.Constructor<?>[] CONSTRUCTORS = CLASS_JFXPANEL.getConstructors();
            for (java.lang.reflect.Constructor<?> constructor : CONSTRUCTORS) {
                if (constructor.getParameterCount() == 0) {
                    return constructor.newInstance();
                }
            }
            return null;
        } catch (ReflectiveOperationException ex) {
            LOG.log(Level.WARNING, "Ошибка инициализации JavaFX.", ex);
            return null;
        }
    }

    /**
     * Try to termiante JavaFX.
     *
     */
    void terminateJavaFX() {
        try {
            if (this.JFXPANEL != null) {
                final Class<?> CLASS_PLATFORM = Class.forName("javafx.application.Platform");
                final java.lang.reflect.Method METHOD_EXIT = CLASS_PLATFORM.getMethod("exit");
                METHOD_EXIT.invoke(null);
            }
        } catch (ReflectiveOperationException | NullPointerException ex) {
            LOG.log(Level.WARNING, "Ошибка завершения JavaFX.", ex);
        }
    }

    /**
     * @return Indicates whether JavaFX was found or not.
     */
    public boolean isJavaFXAvailable() {
        return this.JFXPANEL != null && PREFS.getBoolean("settings.javafx", true);
    }

    /**
     * Used to render cells.
     */
    final private class ModListCellRenderer implements ListCellRenderer<Mod> {

        @Override
        public Component getListCellRendererComponent(JList list, Mod value, int index, boolean isSelected, boolean cellHasFocus) {
            if (null == value) {
                return RENDERER.getListCellRendererComponent(list, null, index, isSelected, cellHasFocus);
            }
            return RENDERER.getListCellRendererComponent(list, value.getName(), index, isSelected, cellHasFocus);
        }

        final private BasicComboBoxRenderer RENDERER = new BasicComboBoxRenderer();
    }

    /**
     * Used to render cells.
     */
    final private class PluginListCellRenderer implements ListCellRenderer<Plugin> {

        @Override
        public Component getListCellRendererComponent(JList list, Plugin value, int index, boolean isSelected, boolean cellHasFocus) {
            if (null == value) {
                return RENDERER.getListCellRendererComponent(list, null, index, isSelected, cellHasFocus);
            }
            return RENDERER.getListCellRendererComponent(list, value.NAME, index, isSelected, cellHasFocus);
        }
        final private BasicComboBoxRenderer RENDERER = new BasicComboBoxRenderer();
    }

    /**
     * Convenience method for grouping menuitems.
     * @param items 
     */
    static void groupMenuItems(AbstractButton... items) {
        ButtonGroup group = new ButtonGroup();
        for (AbstractButton item : items) {
            group.add(item);
        }
    }
    
    static void fillMenu(JMenu menu, JMenuItem... items) {
        for (AbstractButton item : items) {
            if (item == null) menu.addSeparator();
            else menu.add(item);
        }        
    }
    
    /**
     * Listener for tree selection events.
     */
    private ESS save;
    private Optional<Opener.Result> openerResult = Optional.empty();
    private Optional<Analysis> analysis;
    private boolean modified;
    private Predicate<Node> filter;
    private Scanner scanner;

    final private MemoryLabel LBL_MEMORY;
    final private JLabel LBL_WATCHING;
    final private JLabel LBL_SCANNING;
    final private FilterTree TREE;
    final private VariableTable TABLE;
    final private InfoPane INFOPANE;
    final private JButton BTN_CLEAR_FILTER;
    final private JScrollPane TREESCROLLER;
    final private JScrollPane DATASCROLLER;
    final private JScrollPane INFOSCROLLER;
    final private JSplitPane MAINSPLITTER;
    final private JSplitPane RIGHTSPLITTER;
    final private JPanel MAINPANEL;
    final private JPanel MODPANEL;
    final private JComboBox<Mod> MODCOMBO;
    final private JComboBox<Plugin> PLUGINCOMBO;
    final private JLabel MODLABEL;
    final private JPanel FILTERPANEL;
    final private JTreeFilterField FILTERFIELD;
    final private JPanel TOPPANEL;
    final private JPanel STATUSPANEL1;
    final private JTreeHistory TREEHISTORY;
    final private JPanel STATUSPANEL2;
    final private JPanel PROGRESSPANEL;
    final private JMenuBar MENUBAR;
    final private JMenu MENU_FILE;
    final private JMenu MENU_FILTER;
    final private JMenu MENU_CLEAN;
    final private JMenu MENU_DATA;
    final private JMenu MENU_OPTIONS;
    final private JMenu MENU_HELP;
    
    final private JMenuItem MI_LOAD;
    final private JMenuItem MI_SAVE;
    final private JMenuItem MI_SAVEAS;
    final private JMenuItem MI_EXIT;
    final private JMenuItem MI_LOADESPS;
    final private JMenuItem MI_LOOKUPID;
    final private JMenuItem MI_LOOKUPBASE;
    final private JMenuItem MI_REMOVEUNATTACHED;
    final private JMenuItem MI_REMOVEUNDEFINED;
    final private JMenuItem MI_RESETHAVOK;
    final private JMenuItem MI_CLEANSEFORMLISTS;
    final private JMenuItem MI_REMOVENONEXISTENT;
    final private JMenuItem MI_BATCHCLEAN;
    final private JMenuItem MI_KILL;
    final private JMenuItem MI_SHOWLONGSTRINGS;
    final private JMenuItem MI_ANALYZE_ARRAYS;
    final private JMenuItem MI_COMPARETO;
    final private JCheckBoxMenuItem MI_USEMO2;
    final private JCheckBoxMenuItem MI_SHOWMODS;
    final private JCheckBoxMenuItem MI_WATCHSAVES;
    final private JMenuItem MI_SHOWLOG;
    final private JMenuItem MI_ABOUT;
    final private JMenuItem MI_SUPPORT;
    final private JMenuItem MI_EXPORTPLUGINS;
    final private JMenuItem MI_SETTINGS;
    final private JCheckBoxMenuItem MI_SHOWUNATTACHED;
    final private JCheckBoxMenuItem MI_SHOWUNDEFINED;
    final private JCheckBoxMenuItem MI_SHOWNOPARENTS;
    final private JCheckBoxMenuItem MI_SHOWMISSINGPARENTS;
    final private JCheckBoxMenuItem MI_SHOWMEMBERLESS;
    final private JCheckBoxMenuItem MI_SHOWCANARIES;
    final private JCheckBoxMenuItem MI_SHOWNULLREFS;
    final private JRadioButtonMenuItem MI_SHOWPARSED0;
    final private JRadioButtonMenuItem MI_SHOWPARSED1;
    final private JRadioButtonMenuItem MI_SHOWPARSED2;
    final private JRadioButtonMenuItem MI_SHOWPARSED3;
    final private JCheckBoxMenuItem MI_SHOWNONEXISTENTCREATED;
    final private JCheckBoxMenuItem MI_SHOWDELETED;
    final private JCheckBoxMenuItem MI_SHOWEMPTY;
    final private JCheckBoxMenuItem MI_SHOWSCRIPTATTACHED;
    final private JValueMenuItem<Duad<Integer>> MI_CHANGEFILTER;
    final private JValueMenuItem<Duad<Integer>> MI_CHANGEFORMFILTER;
    final private JValueMenuItem<String> MI_CHANGEFORMCONTENTFILTER;
    final private LogWindow LOGWINDOW;
    final private Watcher WATCHER;
    final private mf.Timer TIMER;
    final private Object JFXPANEL;

    static final private java.util.prefs.Preferences PREFS = java.util.prefs.Preferences.userNodeForPackage(resaver.ReSaver_Renewed.class);
    static final private Logger LOG = Logger.getLogger(SaveWindow.class.getCanonicalName());
    static final private Pattern URLPATTERN = Pattern.compile("(?<type>[a-z]+):\\/\\/(?<address>[^\\[\\]]+)(?:\\[(?<target1>\\d+)\\])?(?:\\[(?<target2>\\d+)\\])?$");

}

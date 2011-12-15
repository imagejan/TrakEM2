package ini.trakem2.imaging.filters;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.gui.YesNoCancelDialog;
import ini.trakem2.display.Display;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.Layer;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;

public class FilterEditor {

	// They are all IFilter, and all have protected fields.
	@SuppressWarnings("rawtypes")
	static public final Class[] available =
		new Class[]{CLAHE.class, EqualizeHistogram.class, GaussianBlur.class,
		            Invert.class, Normalize.class, RankFilter.class,
		            SubtractBackground.class,
		            LUTRed.class, LUTGreen.class, LUTBlue.class,
		            LUTMagenta.class, LUTCyan.class, LUTYellow.class};

	static private class TableAvailableFilters extends JTable {
		public TableAvailableFilters(final TableChosenFilters tcf) {
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(int rowIndex, int columnIndex) {
					return available[rowIndex].getSimpleName();
				}
				@Override
				public int getRowCount() {
					return available.length;
				}
				@Override
				public int getColumnCount() {
					return 1;
				}
				@Override
				public String getColumnName(int col) {
					return "Available Filters";
				}
			});
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent me) {
					if (2 == me.getClickCount()) {
						tcf.add(available[getSelectedRow()]);
					}
				}
			});
		}
	}
	
	static private class FilterWrapper {
		IFilter filter;
		final Field[] fields;
		FilterWrapper(Class<?> filterClass) {
			this.fields = filterClass.getDeclaredFields();
			try {
				this.filter = (IFilter) filterClass.getConstructor().newInstance();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		/** Makes a copy of {@param filter}. */
		FilterWrapper(IFilter filter) {
			this.fields = filter.getClass().getDeclaredFields();
			try {
				// Create a copy and set its parameters to the same values
				this.filter = (IFilter) filter.getClass().getConstructor().newInstance();
				for (Field f : fields) {
					f.set(this.filter, f.get(filter));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		FilterWrapper() {
			this.fields = new Field[0]; // empty
		}
		String name(int i) {
			return fields[i].getName();
		}
		String value(int i) {
			try {
				return "" + fields[i].get(filter);
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}
		void set(int i, Object v) {
			Utils.log2("v class: " + v.getClass());
			Field f = fields[i];
			f.setAccessible(true);
			Class<?> c = f.getType();
			try {
				String sv = v.toString().trim();
				Utils.log2("sv is: " + sv + ", and f.getDeclaringClass() == " + c);
				if (Double.TYPE == c) v = Double.parseDouble(sv);
				else if (Float.TYPE == c) v = Float.parseFloat(sv);
				else if (Integer.TYPE == c) v = Integer.parseInt(sv);
				else if (Long.TYPE == c) v = Long.parseLong(sv);
				else if (Short.TYPE == c) v = Short.parseShort(sv);
				else if (Byte.TYPE == c) v = Byte.parseByte(sv);
				else if (String.class == c) v = sv;
				//
				f.set(filter, v);
			} catch (Exception e) {
				Utils.logAll("New value '" + v + "' is invalid; keeping the last value.");
				e.printStackTrace();
			}
		}
		public boolean sameParameterValues(FilterWrapper w) {
			if (this.filter == w.filter) return true;
			if (filter.getClass() != w.filter.getClass()) return false;
			for (int i=0; i<fields.length; ++i) {
				if (!value(i).equals(w.value(i))) {
					return false;
				}
			}
			return true;
		}
	}

	static private class TableChosenFilters extends JTable {
		private final ArrayList<FilterWrapper> filters;
		public TableChosenFilters(final ArrayList<FilterWrapper> filters) {
			this.filters = filters;
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(int rowIndex, int columnIndex) {
					switch (columnIndex) {
					case 0: return rowIndex + 1;
					case 1: return filters.get(rowIndex).filter.getClass().getSimpleName();
					}
					return null;
				}
				@Override
				public int getRowCount() {
					return filters.size();
				}
				@Override
				public int getColumnCount() {
					return 2;
				}
				@Override
				public String getColumnName(int col) {
					switch (col) {
						case 0: return "";
						case 1: return "Chosen Filters";
					}
					return null;
				}
			});
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent me) {
					if (me.getClickCount() == 2) {
						filters.remove(getSelectedRow());
						((AbstractTableModel)getModel()).fireTableStructureChanged();
						getColumnModel().getColumn(0).setMaxWidth(10);
					}
				}
			});
			addKeyListener(new KeyAdapter() {
				@Override
				public void keyPressed(KeyEvent ke) {
					// Check preconditions
					int row = getSelectedRow();
					if (-1 == row) return;
					int selRow = -1;
					//
					switch (ke.getKeyCode()) {
					case KeyEvent.VK_PAGE_UP:
						if (filters.size() > 1 && row > 0) {
							filters.add(row -1, filters.remove(row));
							selRow = row -1;
							ke.consume();
						}
						break;
					case KeyEvent.VK_PAGE_DOWN:
						if (filters.size() > 1 && row < filters.size() -1) {
							filters.add(row + 1, filters.remove(row));
							selRow = row + 1;
							ke.consume();
						}
						break;
					case KeyEvent.VK_DELETE:
						if (filters.size() > 1) {
							if (0 == row) selRow = 0;
							else if (filters.size() -1 == row) selRow = filters.size() -2;
							else selRow = row -1;
						}
						filters.remove(row);
						ke.consume();
						break;
					case KeyEvent.VK_UP:
						selRow = row > 0 ? row -1 : row;
						ke.consume();
						break;
					case KeyEvent.VK_DOWN:
						selRow = row < filters.size() -1 ? row + 1 : row;
						ke.consume();
						break;
					}
					((AbstractTableModel)getModel()).fireTableStructureChanged();
					getColumnModel().getColumn(0).setMaxWidth(10);
					if (-1 != selRow) {
						getSelectionModel().setSelectionInterval(selRow, selRow);
					}
				}
			});
			getColumnModel().getColumn(0).setMaxWidth(10);
		}
		final FilterWrapper selected() {
			int row = getSelectedRow();
			if (-1 == row) return new FilterWrapper(); // empty
			return filters.get(row);
		}
		final void add(Class<?> filterClass) {
			filters.add(new FilterWrapper(filterClass));
			((AbstractTableModel)getModel()).fireTableStructureChanged();
			this.getSelectionModel().setSelectionInterval(filters.size()-1, filters.size()-1);
		}
		final void setupListener(final TableParameters tp) {
			getSelectionModel().addListSelectionListener(new ListSelectionListener() {
				@Override
				public void valueChanged(ListSelectionEvent e) {
					tp.triggerUpdate();
				}
			});
		}
	}
	
	static private class TableParameters extends JTable {
		TableParameters(final TableChosenFilters tcf) {
			setModel(new AbstractTableModel() {
				@Override
				public Object getValueAt(int rowIndex, int columnIndex) {
					FilterWrapper w = tcf.selected();
					switch (columnIndex) {
					case 0:
						return w.name(rowIndex);
					case 1:
						return w.value(rowIndex);
					}
					return null;
				}
				@Override
				public int getRowCount() {
					return tcf.selected().fields.length;
				}
				
				@Override
				public int getColumnCount() {
					return 2;
				}
				
				@Override
				public String getColumnName(int col) {
					switch (col) {
					case 0: return "Parameter";
					case 1: return "Value";
					default:
						return null;
					}
				}
				
				@Override
				public void setValueAt(Object v, int rowIndex, int columnIndex) {
					tcf.selected().set(rowIndex, v);
			    }
				
				@Override
				public boolean isCellEditable(int rowIndex, int columnIndex) {
					return 1 == columnIndex;
				}
			});
		}
		void triggerUpdate() {
			((AbstractTableModel)getModel()).fireTableStructureChanged();
		}
	}

	static public final void GUI(final Collection<Patch> patches, final Patch reference) {
		final ArrayList<FilterWrapper> filters = new ArrayList<FilterWrapper>();
		
		// Find out if all images have the exact same filters
		StringBuilder sb = new StringBuilder();
		final Patch ref = (null == reference? patches.iterator().next() : reference);
		final IFilter[] refFilters = ref.getFilters();
		if (null != refFilters) {
			for (IFilter f : refFilters) filters.add(new FilterWrapper(f)); // makes a copy of the IFilter
		}
		//
		for (Patch p : patches) {
			if (ref == p) continue;
			IFilter[] fs = p.getFilters();
			if (null == fs && null == refFilters) continue; // ok
			if ((null != refFilters && null == fs)
			 || (null == refFilters && null != fs)
			 || (null != refFilters && null != fs && fs.length != refFilters.length)) {
				sb.append("WARNING: patch #" + p.getId() + " has a different number of filters than reference patch #" + ref.getId());
				continue;
			}
			// Compare each
			for (int i=0; i<refFilters.length; ++i) {
				if (fs[i].getClass() != refFilters[i].getClass()) {
					sb.append("WARNING: patch #" + p.getId() + " has a different filters than reference patch #" + ref.getId());
					break;
				}
				// Does the filter have the same parameters?
				if (!filters.get(i).sameParameterValues(new FilterWrapper(fs[i]))) {
					sb.append("WARNING: patch #" + p.getId() + " has filter '" + fs[i].getClass().getSimpleName() + "' with different parameters than the reference patch #" + ref.getId());
				}
			}
		}
		if (sb.length() > 0) {
			GenericDialog gd = new GenericDialog("WARNING", null == Display.getFront() ? IJ.getInstance() : Display.getFront().getFrame());
			gd.addMessage("Filters are not all the same for all images:");
			gd.addTextAreas(sb.toString(), null, 20, 30);
			String[] s = new String[]{"Use the filters of the reference image", "Start from an empty list of filters"};
			gd.addChoice("Do:", s, s[0]);
			gd.showDialog();
			if (gd.wasCanceled()) return;
			if (1 == gd.getNextChoiceIndex()) filters.clear();
		}
		
		TableChosenFilters tcf = new TableChosenFilters(filters);
		TableParameters tp = new TableParameters(tcf);
		tcf.setupListener(tp);
		TableAvailableFilters taf = new TableAvailableFilters(tcf);
		
		if (filters.size() > 0) {
			tcf.getSelectionModel().setSelectionInterval(0, 0);
		}
		
		final JFrame frame = new JFrame("Image filters");
		JButton set = new JButton("Set");
		final JComboBox pulldown = new JComboBox(new String[]{"Selected images (" + patches.size() + ")", "All images in layer " + ref.getLayer().getParent().indexOf(ref.getLayer()), "All images in layer range..."});

		final Component[] cs = new Component[]{set, pulldown, tcf, tp, taf};
		
		set.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (check(frame, filters)) {
					ArrayList<Patch> ps = new ArrayList<Patch>();
					switch (pulldown.getSelectedIndex()) {
					case 0:
						ps.addAll(patches);
						break;
					case 1:
						for (Displayable d : ref.getLayer().getDisplayables(Patch.class)) {
							ps.add((Patch)d);
						}
						break;
					case 2:
						GenericDialog gd = new GenericDialog("Apply filters");
						Utils.addLayerRangeChoices(ref.getLayer(), gd);
						gd.addStringField("Image title matches:", "", 30);
						gd.addCheckbox("Visible images only", true);
						gd.showDialog();
						if (gd.wasCanceled()) return;
						String regex = gd.getNextString();
						final boolean visible_only = gd.getNextBoolean();
						Pattern pattern = null;
						if (0 != regex.length()) {
							pattern = Pattern.compile(regex);
						}
						for (Layer l : ref.getLayer().getParent().getLayers(gd.getNextChoiceIndex(), gd.getNextChoiceIndex())) {
							for (Displayable d : l.getDisplayables(Patch.class, visible_only)) {
								if (null != pattern && !pattern.matcher(d.getTitle()).matches()) {
									continue;
								}
								ps.add((Patch)d);
							}
						}
					}
					apply(ps, filters, cs, false);
				}
			}
		});
		JPanel buttons = new JPanel();
		JLabel label = new JLabel("Listing filters of " + patches.size() + " image" + (patches.size() > 1 ? "s":""));
		GridBagConstraints c2 = new GridBagConstraints();
		GridBagLayout gb2 = new GridBagLayout();
		buttons.setLayout(gb2);
		
		c2.anchor = GridBagConstraints.WEST;
		c2.gridx = 0;
		gb2.setConstraints(label, c2);
		buttons.add(label);
		
		c2.gridx = 1;
		c2.weightx = 1;
		JPanel empty = new JPanel();
		gb2.setConstraints(empty, c2);
		buttons.add(empty);
		
		c2.gridx = 2;
		c2.weightx = 0;
		c2.anchor = GridBagConstraints.EAST;
		JLabel a = new JLabel("Apply to:");
		gb2.setConstraints(a, c2);
		buttons.add(a);
		
		c2.gridx = 3;
		c2.insets = new Insets(4, 10, 4, 0);
		gb2.setConstraints(pulldown, c2);
		buttons.add(pulldown);
		
		c2.gridx = 4;
		gb2.setConstraints(set, c2);
		buttons.add(set);
		
		//
		taf.setPreferredSize(new Dimension(350, 500));
		tcf.setPreferredSize(new Dimension(350, 250));
		tp.setPreferredSize(new Dimension(350, 250));
		//
		GridBagLayout gb = new GridBagLayout();
		GridBagConstraints c = new GridBagConstraints();
		
		JPanel all = new JPanel();
		all.setBackground(Color.white);
		all.setPreferredSize(new Dimension(700, 500));
		all.setLayout(gb);
		
		c.gridx = 0;
		c.gridy = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.fill = GridBagConstraints.BOTH;
		c.gridheight = 2;
		c.weightx = 0.5;
		JScrollPane p1 = new JScrollPane(taf);
		p1.setPreferredSize(taf.getPreferredSize());
		gb.setConstraints(p1, c);
		all.add(p1);
		
		c.gridx = 1;
		c.gridy = 0;
		c.gridheight = 1;
		c.weighty = 0.7;
		JScrollPane p2 = new JScrollPane(tcf);
		p2.setPreferredSize(tcf.getPreferredSize());
		gb.setConstraints(p2, c);
		all.add(p2);
		
		c.gridx = 1;
		c.gridy = 1;
		c.weighty = 0.3;
		JScrollPane p3 = new JScrollPane(tp);
		p3.setPreferredSize(tp.getPreferredSize());
		gb.setConstraints(p3, c);
		all.add(p3);
		
		c.gridx = 0;
		c.gridy = 2;
		c.gridwidth = 2;
		c.weightx = 1;
		c.weighty = 0;
		gb.setConstraints(buttons, c);
		all.add(buttons);

		frame.getContentPane().add(all);
		frame.pack();
		frame.setVisible(true);
	}

	private static boolean check(JFrame frame, List<FilterWrapper> wrappers) {
		if (!wrappers.isEmpty()) {
			String s = sanityCheck(wrappers);
			return 0 == s.length()
					|| new YesNoCancelDialog(frame, "WARNING", s + "\nContinue?").yesPressed();
		}
		return true;
	}

	private static String sanityCheck(List<FilterWrapper> wrappers) {
		// Check all variables, find any numeric ones whose value is zero
		// Check for duplicated filters
		final HashSet<Class<?>> unique = new HashSet<Class<?>>();
		for (FilterWrapper w : wrappers) {
			unique.add(w.filter.getClass());
		}
		StringBuilder sb = new StringBuilder();
		if (wrappers.size() != unique.size()) {
			sb.append("WARNING: there are repeated filters!\n");
		}
		for (FilterWrapper w : wrappers) {
			for (Field f : w.fields) {
				try {
					String zero = f.get(w.filter).toString();
					if ("0".equals(zero) || "0.0".equals(zero)) {
						sb.append("WARNING: parameter '" + f.getName() + "' of filter '" + w.filter.getClass().getSimpleName() + "' is zero!\n");
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		return sb.toString();
	}

	private static IFilter[] asFilters(List<FilterWrapper> wrappers) {
		if (wrappers.isEmpty()) return null;
		IFilter[] fs = new IFilter[wrappers.size()];
		int next = 0;
		for (final FilterWrapper fw : wrappers) {
			fs[next++] = new FilterWrapper(fw.filter).filter; // a copy
		}
		return fs;
	}
	
	private static void apply(final Collection<Patch> patches, final List<FilterWrapper> wrappers, final Component[] cs, final boolean append) {
		Bureaucrat.createAndStart(new Worker.Task("Set filters") {
			@Override
			public void exec() {
				try {
					for (Component c : cs) c.setEnabled(false);
					// Undo step
					LayerSet ls = patches.iterator().next().getLayerSet();
					ls.addDataEditStep(new HashSet<Displayable>(patches));
					//
					ArrayList<Future<?>> fus = new ArrayList<Future<?>>();
					for (final Patch p : patches) {
						IFilter[] fs = asFilters(wrappers); // each Patch gets a copy
						if (append) p.appendFilters(fs);
						else p.setFilters(fs);
						Utils.log("calling decache");
						p.getProject().getLoader().decacheImagePlus(p.getId());
						Utils.log(" after: " + p.getProject().getLoader().isImagePlusCached(p));
						fus.add(p.updateMipMaps());
					}
					Utils.wait(fus);
					// Current state
					ls.addDataEditStep(new HashSet<Displayable>(patches));
				} finally {
					for (Component c : cs) c.setEnabled(true);
				}
			}
		}, patches.iterator().next().getProject());
	}
	
	static public final IFilter[] duplicate(final IFilter[] fs) {
		if (null == fs) return fs;
		IFilter[] copy = new IFilter[fs.length];
		for (int i=0; i<fs.length; ++i) copy[i] = new FilterWrapper(fs[i]).filter;
		return copy;
	}
}

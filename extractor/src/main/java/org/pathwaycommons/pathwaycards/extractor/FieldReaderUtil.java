package org.pathwaycommons.pathwaycards.extractor;

import org.biopax.paxtools.controller.PathAccessor;
import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.level3.*;

import java.util.*;

/**
 * @author Ozgun Babur
 */
public class FieldReaderUtil
{
	private static PathAccessor MODIFS = new PathAccessor(
		"PhysicalEntity/feature:ModificationFeature/modificationType/term");

	/**
	 * Reads differential activity labels (active and inactive) of two physical entities. These
	 * labels are used by NCI PID
	 */
	public static int readDifferentialActivity(PhysicalEntity pe1, PhysicalEntity pe2)
	{
		Set<String> feat1 = readModificationNames(pe1);
		Set<String> feat2 = readModificationNames(pe2);
		boolean active1 = feat1.contains("residue modification, active");
		boolean active2 = feat2.contains("residue modification, active");
		boolean inactive1 = feat1.contains("residue modification, inactive");
		boolean inactive2 = feat2.contains("residue modification, inactive");

		if (active2 && !active1) return 1;
		if (inactive2 && !inactive1) return -1;
		if (inactive1 && !inactive2) return 1;
		if (active1 && !active2) return -1;
		return 0;
	}

	public static Set<String> readModificationNames(PhysicalEntity pe)
	{
		return new HashSet<>(MODIFS.getValueFromBean(pe));
	}

	public static String toString(SequenceLocation loc)
	{
		if (loc instanceof SequenceSite)
		{
			SequenceSite ss = (SequenceSite) loc;
			int pos = ss.getSequencePosition();
			if (pos > 0) return "@" + pos;
		}
		else if (loc instanceof SequenceInterval)
		{
			SequenceInterval si = (SequenceInterval) loc;
			SequenceSite bs = si.getSequenceIntervalBegin();
			SequenceSite es = si.getSequenceIntervalEnd();
			if (bs != null && es != null)
			{
				int b = bs.getSequencePosition();
				int e = es.getSequencePosition();
				if (b > 0 && e > 0)
				{
					return "@" + b + "-" + e;
				}
			}
		}
		return "";
	}

	public static List readFeatures(PhysicalEntity pe)
	{
		return toJASON(pe.getFeature());
	}

	public static List readNegativeFeatures(PhysicalEntity pe)
	{
		return toJASON(pe.getNotFeature());
	}

	private static List toJASON(Set<EntityFeature> efs)
	{
		List list = new ArrayList();
		for (EntityFeature ef : efs)
		{
			if (ef instanceof ModificationFeature)
			{
				ModificationFeature mf = (ModificationFeature) ef;
				SequenceModificationVocabulary type = mf.getModificationType();
				Set<String> terms = type.getTerm();
				if (!terms.isEmpty())
				{
					String term = terms.iterator().next();
					term = mapModificationTerm(term);

					SequenceLocation loc = mf.getFeatureLocation();
					String locStr = toString(loc);
					Map map = new LinkedHashMap();
					list.add(map);
					map.put("feature_type", "modification");
					map.put("modification_type", term);
					if (locStr != null) map.put("position", locStr);
				}
			}
			else if (ef instanceof BindingFeature)
			{
				BindingFeature bf = (BindingFeature) ef;
				BindingFeature bindsTo = bf.getBindsTo();
				if (bindsTo != null)
				{
					EntityReference er = bindsTo.getEntityFeatureOf();
					String id = getGroundingIDOrName(er);
					if (id != null)
					{
						Map map = new LinkedHashMap();
						list.add(map);
						map.put("feature_type", "binding");
						map.put("bound_to", id);
					}
				}
			}
		}
		return list;
	}

	/**
	 * Finds the HGNC symbol of the given entity.
	 * @param xrable
	 * @return
	 */
	public static String getHGNCSymbol(XReferrable xrable)
	{
		return getXrefID(xrable, "hgnc symbol");
	}

	/**
	 * Finds the PubChem ID of the given entity.
	 */
	public static String getPubChemID(XReferrable xrable)
	{
		return getXrefID(xrable, "pubchem");
	}

	/**
	 * Finds the ID of desired type of the given entity.
	 */
	public static String getXrefID(XReferrable xrable, String dbStr)
	{
		for (Xref xref : xrable.getXref())
		{
			String db = xref.getDb();
			if (db != null)
			{
				db = db.toLowerCase();
				if (db.equals(dbStr))
				{
					String id = xref.getId();
					if (id != null) return id;
				}
			}
		}

		if (xrable instanceof SimplePhysicalEntity)
		{
			EntityReference er = ((SimplePhysicalEntity) xrable).getEntityReference();
			return getXrefID(er, dbStr);
		}

		return null;
	}

	public static String getUniProtName(Named named)
	{
		for (String name : named.getName())
		{
			if (name.endsWith("_HUMAN")) return "Uniprot:" + name;
		}
		return null;
	}

	public static String getGroundingIDOrName(BioPAXElement pe)
	{
		String s = getGroundingID(pe);
		if (s == null) s = pickAName((Named) pe);
		return s;
	}

	public static String getGroundingID(BioPAXElement pe)
	{
		if (pe instanceof Protein || pe instanceof ProteinReference)
		{
			String s = getUniProtName((Named) pe);
			if (s != null) return s;
		}
		else if (pe instanceof SmallMolecule)
		{
			String s = getPubChemID((XReferrable) pe);
			if (s != null) return s;
		}

		return getHGNCSymbol((XReferrable) pe);
	}

	public static boolean isGeneric(PhysicalEntity pe)
	{
		if (!pe.getMemberPhysicalEntityOf().isEmpty()) return true;

		String sym = getHGNCSymbol(pe);

		if (sym != null) return false;

		if (pe instanceof SimplePhysicalEntity)
		{
			EntityReference er = ((SimplePhysicalEntity) pe).getEntityReference();
			if (er != null && !er.getEntityReferenceOf().isEmpty()) return true;
		}
		return false;
	}

	public static String pickAName(Named named)
	{
		String s = named.getDisplayName();
		if (s != null && !s.isEmpty()) return s;
		s = named.getStandardName();
		if (s != null && !s.isEmpty()) return s;
		return named.getName().iterator().next();
	}

	public static Object convertToJASON(PhysicalEntity pe)
	{
		// Write generics
		if (!pe.getMemberPhysicalEntity().isEmpty())
		{
			Map map = new LinkedHashMap();
			map.put("entity_type", "protein_family");
			map.put("entity_text", pickAName(pe));
			List list = new ArrayList();
			map.put("family_members", list);

			for (PhysicalEntity mem : pe.getMemberPhysicalEntity())
			{
				list.add(convertToJASON(mem));
			}

			return map;
		}

		// Write complexes
		if (pe instanceof Complex)
		{
			if (!((Complex) pe).getComponent().isEmpty())
			{
				List list = new ArrayList();

				for (PhysicalEntity mem : ((Complex) pe).getComponent())
				{
					list.add(convertToJASON(mem));
				}

				return list;
			}
			else
			{
				Map map = new LinkedHashMap();
				map.put("entity_type", "protein_family");
				map.put("entity_text", pickAName(pe));
				return map;
			}
		}

		// Write simple molecules
		Map map = new LinkedHashMap();
		String upn = getUniProtName(pe);
		String sym = getHGNCSymbol(pe);
		String name = pickAName(pe);

		String type = pe instanceof Protein ? "protein" : pe instanceof SmallMolecule ? "chemical" :
			pe instanceof Rna ? "RNA" : pe instanceof Dna ? "DNA" : "Unclassified";

		map.put("entity_type", type);
		map.put("entity_text", name);
		if (upn != null || sym != null) map.put("identifier", upn != null ? upn : sym);

		List featList = readFeatures(pe);
		List negFeatList = readNegativeFeatures(pe);

		if (!featList.isEmpty()) map.put("features", featList);
		if (!negFeatList.isEmpty()) map.put("not_features", negFeatList);

		return map;
	}

	/**
	 * Gets the big mechanism evaluation term for protein modifications, corresponding to the given
	 * PC modification term.
	 */
	public static String mapModificationTerm(String pcTerm)
	{
		pcTerm = pcTerm.toLowerCase();

		if (pcTerm.contains("phospho")) return       "Phosphorylation";
		else if (pcTerm.contains("acetyl")) return   "Acetylation";
		else if (pcTerm.contains("farnesyl")) return "Farnesylation";
		else if (pcTerm.contains("glyco")) return    "Glycosylation";
		else if (pcTerm.contains("hydroxy")) return  "Hydroxylation";
		else if (pcTerm.contains("methyl")) return   "Methylation";
		else if (pcTerm.contains("ribosyl")) return  "Ribosylation";
		else if (pcTerm.contains("sumoyl")) return   "Sumoylation";
		else if (pcTerm.contains("ubiq")) return     "Ubiquitination";
		return "Unrecognized: " + pcTerm;
	}
}

package az.ideanest.project.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.project.domain.Category;
import az.ideanest.project.domain.CategoryTranslation;
import az.ideanest.project.domain.Subcategory;
import az.ideanest.project.domain.SubcategoryTranslation;
import az.ideanest.project.domain.Tag;
import az.ideanest.project.infrastructure.CategoryRepository;
import az.ideanest.project.infrastructure.CategoryTranslationRepository;
import az.ideanest.project.infrastructure.SubcategoryRepository;
import az.ideanest.project.infrastructure.SubcategoryTranslationRepository;
import az.ideanest.project.infrastructure.TagRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.PlatformStaff;
import az.ideanest.shared.access.StaffCapability;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Editing the taxonomy without a deployment — §4.3 and §4.11's AD-08, issue #309.
 *
 * <h2>What #309 was actually blocked on</h2>
 *
 * <p>The issue says "§4.3 requires the taxonomy be editable without a deployment, and no
 * API exposes it". The tables have existed since V6 and V11; what did not exist was any
 * way to write to them, and the entities said so in as many words — "nothing in the
 * application creates a category; the migration does". So this is the endpoint half of a
 * feature whose schema was already there, in the same way the audit log viewer was.
 *
 * <h2>A slug is permanent and a name is not</h2>
 *
 * <p>The slug is in the public URL of every campaign filed under a category, and the
 * platform has no redirect table — so renaming one breaks every link anybody has shared,
 * silently, with no way to find out how many. The display names and the translations are
 * what this screen edits. A category whose slug is genuinely wrong is retired and
 * replaced, which is a decision somebody makes rather than a side effect of fixing a typo.
 *
 * <h2>Nothing here deletes</h2>
 *
 * <p>Not for want of a verb. {@code projects.category_id} references these rows, so a
 * delete either fails on the constraint or, with a cascade nobody should write, takes
 * campaigns with it. Retiring a category — hiding it from the campaign editor while
 * leaving the campaigns filed under it — needs a column V6 does not have, and adding one
 * is a migration this issue does not carry. The screen says so rather than offering a
 * button that returns a constraint violation.
 *
 * <h2>Everything is audited</h2>
 *
 * <p>Renaming a category rewrites what several thousand campaigns say they are, on every
 * page they appear on, with no notice to the creators. §17 makes that a privileged action
 * whether or not it feels like one.
 */
@Service
public class TaxonomyAdministration {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyAdministration.class);

    private final CategoryRepository categories;
    private final SubcategoryRepository subcategories;
    private final CategoryTranslationRepository categoryTranslations;
    private final SubcategoryTranslationRepository subcategoryTranslations;
    private final TagRepository tags;
    private final PlatformStaff staff;
    private final AuditLog audit;

    public TaxonomyAdministration(
            CategoryRepository categories,
            SubcategoryRepository subcategories,
            CategoryTranslationRepository categoryTranslations,
            SubcategoryTranslationRepository subcategoryTranslations,
            TagRepository tags,
            PlatformStaff staff,
            AuditLog audit) {
        this.categories = categories;
        this.subcategories = subcategories;
        this.categoryTranslations = categoryTranslations;
        this.subcategoryTranslations = subcategoryTranslations;
        this.tags = tags;
        this.staff = staff;
        this.audit = audit;
    }

    /**
     * The whole taxonomy, for the screen that edits it.
     *
     * <p>Read whole rather than paged. There are tens of categories and hundreds of
     * subcategories, the screen is a tree, and a tree that pages is a tree nobody can
     * reorder.
     */
    @Transactional(readOnly = true)
    public TaxonomyTree tree(UUID staffId) {
        staff.requireCapability(staffId, StaffCapability.CURATE);

        return new TaxonomyTree(
                categories.findAll(),
                subcategories.findAll(),
                categoryTranslations.findAll(),
                subcategoryTranslations.findAll());
    }

    /**
     * Adds a category.
     *
     * @throws TaxonomySlugTakenException when the handle is in use. V6 has a unique index
     *     on it; checking the violation rather than reading first avoids the race between
     *     two administrators adding the same category
     */
    @Transactional
    public Category createCategory(UUID staffId, String slug, String nameAz, String nameEn, int sortOrder) {
        staff.requireCapability(staffId, StaffCapability.CURATE);

        Category created = new Category(Identifiers.newIdentifier(), slug, nameAz, nameEn, sortOrder);
        Category saved;
        try {
            saved = categories.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new TaxonomySlugTakenException(slug);
        }

        record(staffId, saved.getId(), "categoryCreated; slug=%s; az=%s; en=%s".formatted(slug, nameAz, nameEn));
        log.info("Category {} created by {}", slug, staffId);
        return saved;
    }

    /** Renames a category and moves it in the navigation. The slug is not touched. */
    @Transactional
    public Category editCategory(UUID staffId, UUID categoryId, String nameAz, String nameEn, int sortOrder) {
        staff.requireCapability(staffId, StaffCapability.CURATE);

        Category category =
                categories.findById(categoryId).orElseThrow(() -> new TaxonomyNotFoundException(categoryId));
        category.rename(nameAz, nameEn);
        category.reorder(sortOrder);

        record(staffId, categoryId, "categoryEdited; az=%s; en=%s; order=%d".formatted(nameAz, nameEn, sortOrder));
        return category;
    }

    /** Adds a subcategory under a category. The parent is permanent — see {@link Subcategory}. */
    @Transactional
    public Subcategory createSubcategory(
            UUID staffId, UUID parentId, String slug, String nameAz, String nameEn, int sortOrder) {

        staff.requireCapability(staffId, StaffCapability.CURATE);
        categories.findById(parentId).orElseThrow(() -> new TaxonomyNotFoundException(parentId));

        Subcategory created =
                new Subcategory(Identifiers.newIdentifier(), parentId, slug, nameAz, nameEn, sortOrder);
        Subcategory saved;
        try {
            saved = subcategories.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            throw new TaxonomySlugTakenException(slug);
        }

        record(staffId, saved.getId(), "subcategoryCreated; parent=%s; slug=%s".formatted(parentId, slug));
        return saved;
    }

    /** Renames a subcategory and moves it within its parent. */
    @Transactional
    public Subcategory editSubcategory(
            UUID staffId, UUID subcategoryId, String nameAz, String nameEn, int sortOrder) {

        staff.requireCapability(staffId, StaffCapability.CURATE);

        Subcategory subcategory = subcategories
                .findById(subcategoryId)
                .orElseThrow(() -> new TaxonomyNotFoundException(subcategoryId));
        subcategory.rename(nameAz, nameEn);
        subcategory.reorder(sortOrder);

        record(staffId, subcategoryId, "subcategoryEdited; az=%s; en=%s".formatted(nameAz, nameEn));
        return subcategory;
    }

    /**
     * Writes or replaces a category's name in one locale.
     *
     * <p>An upsert, because "add a translation" and "correct a translation" are the same
     * intent from the screen's point of view — and a client that had to know which verb to
     * send would choose from a list it may have loaded before somebody else added the row.
     */
    @Transactional
    public CategoryTranslation translateCategory(UUID staffId, UUID categoryId, String locale, String name) {
        staff.requireCapability(staffId, StaffCapability.CURATE);
        categories.findById(categoryId).orElseThrow(() -> new TaxonomyNotFoundException(categoryId));

        CategoryTranslation.Key key = new CategoryTranslation.Key(categoryId, locale);
        CategoryTranslation translation = categoryTranslations
                .findById(key)
                .map(found -> {
                    found.rename(name);
                    return found;
                })
                .orElseGet(() -> categoryTranslations.save(new CategoryTranslation(key, name)));

        record(staffId, categoryId, "categoryTranslated; locale=%s".formatted(locale));
        return translation;
    }

    /** The same for a subcategory. */
    @Transactional
    public SubcategoryTranslation translateSubcategory(
            UUID staffId, UUID subcategoryId, String locale, String name) {

        staff.requireCapability(staffId, StaffCapability.CURATE);
        subcategories.findById(subcategoryId).orElseThrow(() -> new TaxonomyNotFoundException(subcategoryId));

        SubcategoryTranslation.Key key = new SubcategoryTranslation.Key(subcategoryId, locale);
        SubcategoryTranslation translation = subcategoryTranslations
                .findById(key)
                .map(found -> {
                    found.rename(name);
                    return found;
                })
                .orElseGet(() -> subcategoryTranslations.save(new SubcategoryTranslation(key, name)));

        record(staffId, subcategoryId, "subcategoryTranslated; locale=%s".formatted(locale));
        return translation;
    }

    /**
     * The tags creators have used, most-used first.
     *
     * <p>Read-only from this screen. §4.3 gives tags no editorial vocabulary — they are
     * created by creators typing them — so a manager that renamed one would be rewriting
     * what somebody said about their own campaign. What the screen is for is seeing which
     * tags exist and how heavily each is used, which is the input to a decision about
     * whether one should become a category.
     */
    @Transactional(readOnly = true)
    public List<Tag> tags(UUID staffId) {
        staff.requireCapability(staffId, StaffCapability.CURATE);
        return tags.findAll();
    }

    private void record(UUID staffId, UUID entityId, String detail) {
        audit.record(
                AuditAction.TAXONOMY_CHANGED,
                entityId,
                AuditActor.moderator(staffId),
                AuditOutcome.SUCCEEDED,
                detail);
    }

    /**
     * The whole taxonomy in one object.
     *
     * <p>Four flat lists rather than a nested tree, and the response assembles the nesting.
     * A nested domain object would have to be built by walking the translations twice, and
     * the shape the screen wants — a category, its subcategories, and every locale of both
     * — is not the shape any of the four tables has.
     */
    public record TaxonomyTree(
            List<Category> categories,
            List<Subcategory> subcategories,
            List<CategoryTranslation> categoryTranslations,
            List<SubcategoryTranslation> subcategoryTranslations) {
    }
}

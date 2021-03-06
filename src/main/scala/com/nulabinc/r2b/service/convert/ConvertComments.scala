package com.nulabinc.r2b.service.convert

import java.util.Locale

import com.nulabinc.backlog.importer.conf.{ConfigBase => BacklogConfigBase}
import com.nulabinc.backlog.importer.domain.{BacklogComment, BacklogCommentDetail}
import com.nulabinc.r2b.actor.convert.utils.ProjectContext
import com.nulabinc.r2b.actor.utils.IssueTag
import com.nulabinc.r2b.conf.ConfigBase
import com.nulabinc.r2b.domain.{RedmineCustomFieldDefinition, RedmineJournal, RedmineJournalDetail}
import com.nulabinc.r2b.utils.IOUtil
import com.osinka.i18n.{Lang, Messages}

/**
  * @author uchida
  */
class ConvertComments(pctx: ProjectContext, issueId: Int) {

  implicit val userLang = if (Locale.getDefault.equals(Locale.JAPAN)) Lang("ja") else Lang("en")

  private val note: Note = new Note(pctx, issueId)
  private var isSetStatus = false

  def execute(journals: Seq[RedmineJournal]): Seq[BacklogComment] = journals.map(getBacklogComment)

  private def getBacklogComment(journal: RedmineJournal): BacklogComment = {
    val details = journal.details.filterNot(note.is)
    val notes = journal.details.filter(note.is)
    BacklogComment(
      content = journal.notes.getOrElse("") + "\n" + getNoteText(notes),
      details = details.map(getBacklogCommentDetail),
      createdUserId = journal.user.map(pctx.userMapping.convert),
      created = journal.createdOn)
  }

  private def getBacklogCommentDetail(detail: RedmineJournalDetail): BacklogCommentDetail = {
    val convertOld = convertOldValue(detail, detail.oldValue)
    val convertNew = convertValue(detail, detail.newValue)

    BacklogCommentDetail(
      property = detail.property,
      name = convertName(detail),
      oldValue = convertOld,
      newValue = convertNew)
  }

  private def convertOldValue(detail: RedmineJournalDetail, value: Option[String]) = {
    val convertOld = convertValue(detail, detail.oldValue)
    if (detail.property == ConfigBase.Property.ATTR && detail.name == ConfigBase.Property.Attr.STATUS) {
      if (isSetStatus) convertOld
      else {
        isSetStatus = true
        pctx.getDefaultStatusName()
      }
    } else convertOld
  }

  private def getNoteText(notes: Seq[RedmineJournalDetail]): String =
    notes.map(note.getValue).mkString("\n")

  private def convertName(detail: RedmineJournalDetail): String = detail.property match {
    case ConfigBase.Property.CUSTOM_FIELD => pctx.getCustomFieldDefinitionsName(detail.name)
    case _ => convertBacklogName(detail.name)
  }

  private def convertBacklogName(name: String): String = name match {
    case ConfigBase.Property.Attr.SUBJECT => BacklogConfigBase.Property.Attr.SUMMARY
    case ConfigBase.Property.Attr.DESCRIPTION => BacklogConfigBase.Property.Attr.DESCRIPTION
    case ConfigBase.Property.Attr.TRACKER => BacklogConfigBase.Property.Attr.ISSUE_TYPE
    case ConfigBase.Property.Attr.STATUS => BacklogConfigBase.Property.Attr.STATUS
    case ConfigBase.Property.Attr.PRIORITY => BacklogConfigBase.Property.Attr.PRIORITY
    case ConfigBase.Property.Attr.ASSIGNED => BacklogConfigBase.Property.Attr.ASSIGNED
    case ConfigBase.Property.Attr.VERSION => BacklogConfigBase.Property.Attr.MILESTONE
    case ConfigBase.Property.Attr.PARENT => BacklogConfigBase.Property.Attr.PARENT
    case ConfigBase.Property.Attr.START_DATE => BacklogConfigBase.Property.Attr.START_DATE
    case ConfigBase.Property.Attr.DUE_DATE => BacklogConfigBase.Property.Attr.DUE_DATE
    case ConfigBase.Property.Attr.ESTIMATED_HOURS => BacklogConfigBase.Property.Attr.ESTIMATED_HOURS
    case ConfigBase.Property.Attr.CATEGORY => BacklogConfigBase.Property.Attr.CATEGORY
    case _ => name
  }

  private def convertValue(detail: RedmineJournalDetail, value: Option[String]): Option[String] = detail.property match {
    case ConfigBase.Property.ATTR => convertAttr(detail.name, value)
    case ConfigBase.Property.CUSTOM_FIELD => convertCf(detail.name, value)
    case ConfigBase.Property.ATTACHMENT => value
    case ConfigBase.Property.RELATION => value
  }

  private def convertAttr(name: String, value: Option[String]): Option[String] = name match {
    case ConfigBase.Property.Attr.STATUS => pctx.getStatusName(value)
    case ConfigBase.Property.Attr.DESCRIPTION => getDescription(value)
    case ConfigBase.Property.Attr.PRIORITY => pctx.getPriorityName(value)
    case ConfigBase.Property.Attr.ASSIGNED => pctx.getUserLoginId(value).map(pctx.userMapping.convert)
    case ConfigBase.Property.Attr.VERSION => pctx.getVersionName(value)
    case ConfigBase.Property.Attr.TRACKER => pctx.getTrackerName(value)
    case ConfigBase.Property.Attr.CATEGORY => pctx.getCategoryName(value)
    case _ => value
  }

  private def getDescription(description: Option[String]): Option[String] =
    description.map(value => {
      val sb = new StringBuilder
      sb.append(value)
      sb.append("\n").append(IssueTag.getTag(issueId, pctx.conf.redmineUrl))
      sb.result()
    })

  private def convertCf(name: String, value: Option[String]): Option[String] = {
    val option = pctx.customFieldDefinitions.find(customField => name.toInt == customField.id)
    if (option.isDefined) {
      val define: RedmineCustomFieldDefinition = pctx.customFieldDefinitions.find(customField => name.toInt == customField.id).get
      define.fieldFormat match {
        case "version" => pctx.getVersionName(value)
        case "user" => pctx.getUserFullname(value)
        case _ => value
      }
    } else value
  }

}

class Note(pctx: ProjectContext, issueId: Int) {

  implicit val userLang = if (Locale.getDefault.equals(Locale.JAPAN)) Lang("ja") else Lang("en")

  def is(detail: RedmineJournalDetail): Boolean =
    RelationNote.is(detail) ||
      DoneRatioNote.is(detail) ||
      PrivateIssueNote.is(detail) ||
      ProjectIdNote.is(detail) ||
      AnonymousUserNote.is(detail) ||
      AttachmentNotFoundNote.is(detail)

  def getValue(detail: RedmineJournalDetail): String =
    if (DoneRatioNote.is(detail)) DoneRatioNote.value(detail)
    else if (PrivateIssueNote.is(detail)) PrivateIssueNote.value(detail)
    else if (RelationNote.is(detail)) RelationNote.value(detail)
    else if (ProjectIdNote.is(detail)) ProjectIdNote.value(detail)
    else if (AnonymousUserNote.is(detail)) AnonymousUserNote.value(detail)
    else if (AttachmentNotFoundNote.is(detail)) AttachmentNotFoundNote.value(detail)
    else ""

  trait Note {

    def is(detail: RedmineJournalDetail): Boolean

    def value(detail: RedmineJournalDetail): String

    def getNote(label: String, detail: RedmineJournalDetail): String =
      Messages(label, getValue(detail.oldValue), getValue(detail.newValue))

    def getMessage(label: String, oldValue: String, newValue: String): String =
      Messages("label.change_comment", Messages(label), oldValue, newValue)

    def getValue(value: Option[String]): String = value.getOrElse(Messages("label.not_set"))

  }

  object RelationNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean = detail.property == ConfigBase.Property.RELATION

    override def value(detail: RedmineJournalDetail): String = getNote("label.relation", detail)
  }

  object DoneRatioNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean =
      detail.property == ConfigBase.Property.ATTR && detail.name == "done_ratio"

    override def value(detail: RedmineJournalDetail): String = getNote("label.done_ratio", detail)
  }

  object PrivateIssueNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean =
      detail.property == ConfigBase.Property.ATTR && detail.name == "is_private"

    override def value(detail: RedmineJournalDetail): String = getNote("label.private", detail)
  }

  object ProjectIdNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean =
      detail.property == ConfigBase.Property.ATTR && detail.name == "project_id"

    override def value(detail: RedmineJournalDetail): String = {
      val oldName = getProjectName(detail.oldValue)
      val newName = getProjectName(detail.newValue)
      getMessage("label.project", oldName, newName)
    }

    private def getProjectName(value: Option[String]): String =
      if (value.isDefined && value.get != "") {
        pctx.getProjectName(value.get.toInt).getOrElse(Messages("label.not_set"))
      } else Messages("label.not_set")
  }

  object AnonymousUserNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean = {
      if (detail.property == ConfigBase.Property.CUSTOM_FIELD) {
        val option = pctx.customFieldDefinitions.find(customField => detail.name.toInt == customField.id)
        if (option.isDefined) {
          val define: RedmineCustomFieldDefinition = option.get
          if (define.fieldFormat == "user") !(pctx.getUserFullname(detail.oldValue).isDefined && pctx.getUserFullname(detail.newValue).isDefined)
          else false
        } else false
      } else false
    }

    override def value(detail: RedmineJournalDetail): String = {
      val oldValue = pctx.getUserFullname(detail.oldValue).orElse(detail.oldValue)
      val newValue = pctx.getUserFullname(detail.newValue).orElse(detail.newValue)
      getMessage("label.user", getValue(oldValue), getValue(newValue))
    }
  }

  object AttachmentNotFoundNote extends Note {
    override def is(detail: RedmineJournalDetail): Boolean = {
      if (detail.property == ConfigBase.Property.ATTACHMENT) {
        val path: String = ConfigBase.Redmine.getIssueAttachmentDir(pctx.project.identifier, issueId, detail.name.toInt)
        !IOUtil.isDirectory(path)
      } else false
    }

    override def value(detail: RedmineJournalDetail): String = {
      if (detail.newValue.isDefined) Messages("message.add_attachment", detail.newValue.get)
      else if (detail.oldValue.isDefined) Messages("message.del_attachment", detail.oldValue.get)
      else ""
    }
  }

}

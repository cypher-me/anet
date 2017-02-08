import React, {Component, PropTypes} from 'react'
import {Grid, Row, Col} from 'react-bootstrap'
import {Link} from 'react-router'
import utils from 'utils'

import LinkTo from 'components/LinkTo'
import {Report, Person, Organization} from 'models'

import moment from 'moment'

export default class ReportSummary extends Component {
	static propTypes = {
		report: PropTypes.object.isRequired,
	}

	render() {
		let report = new Report(this.props.report)

		return <Grid fluid>
			<Row>
				<Col md={6}>
					{report.advisorOrg &&
						<LinkTo organization={new Organization(report.advisorOrg)} />
					} ->&nbsp;
					{report.principalOrg &&
						<LinkTo organization={new Organization(report.principalOrg)} />
					}
				</Col>
				<Col md={6}>
					{report.engagementDate && moment(report.engagementDate).format("D MMM YYYY")}
					{report.location &&
						<span> @&nbsp;
							<Link to={"/locations/" + report.location.id}>{report.location.name}</Link>
						</span>
					}
				</Col>
			</Row>
			<Row>
				<Col md={6}>{report.primaryAdvisor && this.renderPerson(report.primaryAdvisor)}</Col>
				<Col md={6}>{report.primaryPrincipal && this.renderPerson(report.primaryPrincipal)}</Col>
			</Row>
			{report.cancelledReason &&
				<Row>
					<Col md={12}>
						Cancelled for: <b>{utils.sentenceCase(report.cancelledReason)}</b>
					</Col>
				</Row>
			}
			<Row>
				<Col md={12}>
					{report.intent}. &nbsp;
					{report.keyOutcomes && <span>The <b>Key Outcomes</b> were {report.keyOutcomes}.</span>} &nbsp;
					{report.nextSteps && <span>The <b>Next Steps</b> are {report.nextSteps}.</span>}
				</Col>
			</Row>
			<Row>
				<Col md={4} mdOffset={8}><LinkTo report={report} >Read Full Report</LinkTo></Col>
			</Row>
		</Grid>
	}

	renderPerson(person) {
		person = new Person(person);
		return <div>
			<img height={20} width={20} src={person.iconUrl()} alt={person.role} className="person-icon" />
			<LinkTo person={person} />
			{person.position && person.position.organization &&
				<span> -&nbsp;
				<LinkTo organization={new Organization(person.position.organization)} />
				</span>
			}
			</div>
	}
}
